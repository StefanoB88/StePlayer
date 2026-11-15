import AVFoundation
import Foundation
import os
import Shared

/// Turns raw player / system errors into what the user reads. Error domains and numeric codes
/// ("CoreMediaErrorDomain -12646", "NSURLErrorDomain -1004", ...) never reach the screen: they go to the
/// log and to the stats overlay only.
enum PlaybackErrorText {
    private static let log = Logger(subsystem: "com.steplayer.app", category: "playback")

    /// "Formato non supportato da questo dispositivo" and similar: the only details a user ever sees.
    static let unsupportedFormat = "Formato non supportato da questo dispositivo"
    static let protectedContent = "Contenuto protetto, non riproducibile su questo dispositivo"
    static let invalidAnswer = "Il server ha inviato una risposta non valida"

    /// The error and its underlying errors, outermost first.
    static func chain(_ error: NSError?) -> [NSError] {
        var out: [NSError] = []
        var current = error
        while let e = current, out.count < 6 {
            out.append(e)
            current = e.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return out
    }

    /// "CoreMediaErrorDomain -12646 ← AVFoundationErrorDomain -11800", for the log and the stats overlay.
    static func code(_ error: NSError?) -> String? {
        let parts = chain(error).map { "\($0.domain) \($0.code)" }
        return parts.isEmpty ? nil : parts.joined(separator: " ← ")
    }

    /// Writes the technical code to the system log (never shown to the user).
    /// Which engine opened which container (never the URL: it carries credentials).
    static func engineChoice(_ engine: String, container: String?, reason: String) {
        log.info("open \(engine, privacy: .public) container=\(container ?? "?", privacy: .public) \(reason, privacy: .public)")
        #if DEBUG
        print("[playback] open \(engine) container=\(container ?? "?") \(reason)")
        #endif
    }

    static func record(_ code: String?, context: String) {
        guard let code else { return }
        log.error("\(context, privacy: .public): \(code, privacy: .public)")
        #if DEBUG
        print("[playback] \(context): \(code)")
        #endif
    }

    /// User-facing error for a failure reported by an engine (AVPlayer / VLC).
    static func playbackError(nsError: NSError?, message: String, container: String?) -> PlaybackError {
        let errors = chain(nsError)
        if let url = errors.first(where: { $0.domain == NSURLErrorDomain }) {
            return url.code == NSURLErrorTimedOut ? PlaybackErrors.shared.timeout() : PlaybackErrors.shared.network()
        }
        if let detail = friendlyDetail(errors) {
            if detail == unsupportedFormat { return unsupported(container) }
            return PlaybackErrors.shared.unknown(detail: detail)
        }
        // No system error: the message was written by us (VLC engine, invalid URL, ...) and is readable.
        if errors.isEmpty, !looksTechnical(message) { return PlaybackErrors.shared.unknown(detail: message) }
        return PlaybackErrors.shared.unknown(detail: nil)
    }

    /// User-facing error for a failure while preparing the stream (resolve, connection lease, ...).
    static func startError(_ error: Error) -> PlaybackError {
        let ns = error as NSError
        if let kotlin = ns.userInfo["KotlinException"] as? KotlinThrowable {
            // The core's own messages are written for the user ("Credenziali mancanti", ...).
            if kotlin is StreamException, let message = kotlin.message, !message.isEmpty { return PlaybackErrors.shared.unknown(detail: message) }
            let text = kotlin.message ?? ""
            if text.contains(NSURLErrorDomain) {
                return text.contains("Code=\(NSURLErrorTimedOut)") ? PlaybackErrors.shared.timeout() : PlaybackErrors.shared.network()
            }
            if let message = kotlin.message, !message.isEmpty, !looksTechnical(message) {
                return PlaybackErrors.shared.unknown(detail: message)
            }
            return PlaybackErrors.shared.unknown(detail: nil)
        }
        return playbackError(nsError: ns, message: "", container: nil)
    }

    /// Format not supported, using the same Italian title as the core.
    private static func unsupported(_ container: String?) -> PlaybackError {
        PlaybackError(kind: .unsupportedFormat, title: "Formato non supportato", message: "\(unsupportedFormat).", retryable: false)
    }

    /// A clear Italian explanation for the common AVFoundation / CoreMedia failures, nil for the rest.
    private static func friendlyDetail(_ errors: [NSError]) -> String? {
        for e in errors {
            if e.domain == AVFoundationErrorDomain, let code = AVError.Code(rawValue: e.code) {
                switch code {
                case .fileFormatNotRecognized, .decoderNotFound, .failedToParse, .decodeFailed, .formatUnsupported,
                     .incompatibleAsset, .noCompatibleAlternatesForExternalDisplay:
                    return unsupportedFormat
                case .contentIsProtected, .contentIsNotAuthorized, .applicationIsNotAuthorizedToUseDevice:
                    return protectedContent
                case .serverIncorrectlyConfigured:
                    return invalidAnswer
                default:
                    break
                }
            }
            if e.domain == "CoreMediaErrorDomain" {
                // Unsupported codec / container / HLS manifest content (same codes AVPlayerEngine maps).
                if [-12847, -12848, -12927, -12642].contains(e.code) { return unsupportedFormat }
            }
        }
        return nil
    }

    /// Text that carries a code or a framework message rather than words written for a person.
    private static func looksTechnical(_ text: String) -> Bool {
        text.range(of: #"(Domain|Error|Exception|Code=|-\d{3,}|\(\w+ -?\d+\.?\))"#, options: .regularExpression) != nil
    }
}

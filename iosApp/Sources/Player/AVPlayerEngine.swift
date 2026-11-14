import AVFoundation
import AVKit
import UIKit
import Shared

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

@MainActor
final class AVPlayerEngine: NSObject, PlayerEngine {
    let player = AVPlayer()
    private let layerView = PlayerLayerView()
    #if os(iOS)
    private(set) var pipController: AVPictureInPictureController?
    private var pipRestoring = false
    /// From "will start" until "did stop" / "failed": covers the moment the app goes to background while the
    /// automatic PiP animation is still running (`isPictureInPictureActive` is not true yet).
    private var pipEngaged = false
    /// Called when the user closes PiP without returning to the app (must tear down).
    var onPictureInPictureClosed: (() -> Void)?
    var isPictureInPictureActive: Bool { pipEngaged || (pipController?.isPictureInPictureActive ?? false) }
    #endif
    private var statusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var observers: [NSObjectProtocol] = []
    private var pendingStart: Double = 0
    private var failedReported = false
    private(set) var hasFailed = false

    var onEnded: (() -> Void)?
    var onError: ((EngineFailure) -> Void)?
    var onReady: (() -> Void)?
    var onStall: (() -> Void)?
    var onStateChange: (() -> Void)?
    var bufferProfile: BufferProfile?
    var subtitleStyle: SubtitleStyle?
    /// AVPlayer has no audio/subtitle offset API.
    let supportsDelays = false
    func setDelays(audioMs: Int64, subtitleMs: Int64) {}

    override init() {
        super.init()
        layerView.backgroundColor = .black
        layerView.playerLayer.player = player
        layerView.playerLayer.videoGravity = .resizeAspect
        player.allowsExternalPlayback = true
        player.automaticallyWaitsToMinimizeStalling = true
        timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in self?.onStateChange?() }
        }
        #if os(iOS)
        if AVPictureInPictureController.isPictureInPictureSupported() {
            let pip = AVPictureInPictureController(playerLayer: layerView.playerLayer)
            pip?.canStartPictureInPictureAutomaticallyFromInline = true
            pip?.delegate = self
            pipController = pip
        }
        #endif
    }

    var view: UIView { layerView }
    var isPlaying: Bool { player.timeControlStatus == .playing }
    var isBuffering: Bool { player.timeControlStatus == .waitingToPlayAtSpecifiedRate }
    var isExternalPlaybackActive: Bool { player.isExternalPlaybackActive }
    var currentTime: Double {
        let t = player.currentTime().seconds
        return t.isFinite ? t : 0
    }
    var duration: Double {
        guard let d = player.currentItem?.duration.seconds, d.isFinite else { return 0 }
        return d
    }

    func load(_ stream: PlayableStream, startAt: Double, title: String, subtitle: String?) {
        stop()
        hasFailed = false
        failedReported = false
        guard let url = PlaybackURL.make(stream.url) else {
            report(EngineFailure(message: "Indirizzo dello stream non valido"))
            return
        }
        let options: [String: Any]? = stream.headers.isEmpty ? nil : ["AVURLAssetHTTPHeaderFieldsKey": stream.headers]
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: options))
        // Buffer profile from the shared BufferAdvisor (0 = let AVFoundation decide).
        item.preferredForwardBufferDuration = bufferProfile?.appleForwardBufferSec ?? (stream.isLive ? 6 : 0)
        player.automaticallyWaitsToMinimizeStalling = true
        if let style = subtitleStyle { item.textStyleRules = Self.textStyleRules(style) }
        #if os(tvOS)
        item.externalMetadata = Self.metadata(title: title, subtitle: subtitle)
        #endif
        pendingStart = startAt
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            Task { @MainActor in self?.statusChanged(item) }
        }
        let nc = NotificationCenter.default
        observers = [
            nc.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.onEnded?() }
            },
            nc.addObserver(forName: .AVPlayerItemPlaybackStalled, object: item, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.onStall?() }
            },
            nc.addObserver(forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self, weak item] note in
                let error = note.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? NSError
                Task { @MainActor in
                    guard let self, let item else { return }
                    self.report(self.failure(for: item, error: error))
                }
            },
        ]
        player.replaceCurrentItem(with: item)
        player.play()
    }

    private func statusChanged(_ item: AVPlayerItem) {
        guard item === player.currentItem else { return }
        switch item.status {
        case .readyToPlay:
            if pendingStart > 1 {
                player.seek(to: CMTime(seconds: pendingStart, preferredTimescale: 600))
                pendingStart = 0
            }
            onReady?()
        case .failed:
            report(failure(for: item, error: item.error as NSError?))
        default:
            break
        }
    }

    private func report(_ failure: EngineFailure) {
        guard !failedReported else { return }
        failedReported = true
        hasFailed = true
        onError?(failure)
    }

    /// Extracts HTTP status and error codes from the item's error log and NSError chain.
    private func failure(for item: AVPlayerItem, error: NSError?) -> EngineFailure {
        var status: Int?
        if let events = item.errorLog()?.events {
            status = events.reversed().first(where: { $0.errorStatusCode >= 400 && $0.errorStatusCode < 600 })?.errorStatusCode
        }
        var unsupported = false
        var chain: [NSError] = []
        var current = error
        while let e = current, chain.count < 5 {
            chain.append(e)
            current = e.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        for e in chain {
            if e.domain == "CoreMediaErrorDomain" {
                switch e.code {
                case -12938: status = status ?? 404
                case -12660: status = status ?? 403
                case -12937: status = status ?? 401
                case -12847, -12848, -12927, -12642: unsupported = true
                default: break
                }
            }
            if e.domain == AVFoundationErrorDomain {
                if [AVError.Code.fileFormatNotRecognized.rawValue, AVError.Code.decoderNotFound.rawValue,
                    AVError.Code.failedToParse.rawValue].contains(e.code) {
                    unsupported = true
                }
            }
        }
        let message = error?.localizedFailureReason ?? error?.localizedDescription ?? "Impossibile riprodurre questo contenuto"
        return EngineFailure(message: message, httpStatus: status, nsError: chain.first { $0.domain == NSURLErrorDomain } ?? error, unsupported: unsupported)
    }

    func play() { player.play() }
    func pause() { player.pause() }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600),
                    toleranceBefore: CMTime(seconds: 0.5, preferredTimescale: 600),
                    toleranceAfter: CMTime(seconds: 0.5, preferredTimescale: 600))
    }

    func stop() {
        player.pause()
        statusObservation?.invalidate()
        statusObservation = nil
        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        if let asset = player.currentItem?.asset { asset.cancelLoading() }
        player.replaceCurrentItem(with: nil)
    }

    /// AVPlayer closes synchronously and cheaply.
    func stopAndWait() -> Task<Void, Never>? {
        stop()
        return nil
    }

    // MARK: Stats

    func statsLines() -> [String] {
        guard let item = player.currentItem else { return ["Nessuno stream"] }
        var lines: [String] = []
        let size = item.presentationSize
        lines.append("Risoluzione  \(Int(size.width))×\(Int(size.height))")
        if let e = item.accessLog()?.events.last {
            lines.append(String(format: "Bitrate      %.2f Mb/s (indicato)", e.indicatedBitrate / 1_000_000))
            lines.append(String(format: "Rete         %.2f Mb/s (osservato)", e.observedBitrate / 1_000_000))
            lines.append("Blocchi      \(e.numberOfStalls)")
            lines.append("Frame persi  \(e.numberOfDroppedVideoFrames)")
        }
        let now = player.currentTime().seconds
        if let range = item.loadedTimeRanges.last?.timeRangeValue, now.isFinite {
            let end = (range.start + range.duration).seconds
            lines.append(String(format: "Buffer       %.1f s", max(0, end - now)))
        }
        lines.append("Profilo      \(bufferProfile?.name ?? "automatico")")
        lines.append("Motore       AVPlayer")
        return lines
    }

    // MARK: Subtitle style

    private static func textStyleRules(_ style: SubtitleStyle) -> [AVTextStyleRule] {
        var attrs: [String: Any] = [
            kCMTextMarkupAttribute_RelativeFontSize as String: NSNumber(value: Double(style.scale) * 100),
        ]
        let fg: [Double] = style.color == SubtitleColor.yellow ? [1, 1, 0, 1] : [1, 1, 1, 1]
        attrs[kCMTextMarkupAttribute_ForegroundColorARGB as String] = [fg[3], fg[0], fg[1], fg[2]]
        switch style.background {
        case SubtitleBackground.opaque: attrs[kCMTextMarkupAttribute_BackgroundColorARGB as String] = [1.0, 0, 0, 0]
        case SubtitleBackground.semiTransparent: attrs[kCMTextMarkupAttribute_BackgroundColorARGB as String] = [0.5, 0, 0, 0]
        default: attrs[kCMTextMarkupAttribute_BackgroundColorARGB as String] = [0.0, 0, 0, 0]
        }
        switch style.edge {
        case SubtitleEdge.outline: attrs[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_Uniform
        case SubtitleEdge.shadow: attrs[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_DropShadow
        default: attrs[kCMTextMarkupAttribute_CharacterEdgeStyle as String] = kCMTextMarkupCharacterEdgeStyle_None
        }
        return AVTextStyleRule(textMarkupAttributes: attrs).map { [$0] } ?? []
    }

    // MARK: Tracks

    func trackInfos() async -> (audio: [TrackInfo], subtitles: [TrackInfo]) {
        func infos(_ group: AVMediaSelectionGroup?, _ options: [AVMediaSelectionOption]) -> [TrackInfo] {
            options.enumerated().map { i, o in
                TrackInfo(id: String(i), language: o.extendedLanguageTag ?? o.locale?.identifier, label: o.displayName,
                          channelCount: 0, isDefault: o == group?.defaultOption,
                          isForced: o.hasMediaCharacteristic(.containsOnlyForcedSubtitles))
            }
        }
        let audio = await options(for: .audible)
        let subs = await options(for: .legible)
        return (infos(audio?.0, audio?.1 ?? []), infos(subs?.0, subs?.1 ?? []))
    }

    private func options(for characteristic: AVMediaCharacteristic) async -> (AVMediaSelectionGroup, [AVMediaSelectionOption])? {
        guard let item = player.currentItem,
              let group = try? await item.asset.loadMediaSelectionGroup(for: characteristic) else { return nil }
        return (group, group.options)
    }

    func audioTracks() async -> [TrackOption] {
        guard let (group, options) = await options(for: .audible), let item = player.currentItem else { return [] }
        let selected = item.currentMediaSelection.selectedMediaOption(in: group)
        return options.enumerated().map { i, o in TrackOption(id: String(i), name: o.displayName, isSelected: o == selected) }
    }

    func subtitleTracks() async -> [TrackOption] {
        guard let (group, options) = await options(for: .legible), let item = player.currentItem else { return [] }
        let selected = item.currentMediaSelection.selectedMediaOption(in: group)
        return options.enumerated().map { i, o in TrackOption(id: String(i), name: o.displayName, isSelected: o == selected) }
    }

    func selectAudio(_ id: String) {
        Task {
            guard let (group, options) = await options(for: .audible), let i = Int(id), i < options.count else { return }
            player.currentItem?.select(options[i], in: group)
        }
    }

    func selectSubtitle(_ id: String?) {
        Task {
            guard let (group, options) = await options(for: .legible) else { return }
            if let id, let i = Int(id), i < options.count {
                player.currentItem?.select(options[i], in: group)
            } else {
                player.currentItem?.select(nil, in: group)
            }
        }
    }

    #if os(tvOS)
    private static func metadata(title: String, subtitle: String?) -> [AVMetadataItem] {
        func item(_ id: AVMetadataIdentifier, _ value: String) -> AVMetadataItem {
            let m = AVMutableMetadataItem()
            m.identifier = id
            m.value = value as NSString
            m.extendedLanguageTag = "und"
            return m.copy() as! AVMetadataItem
        }
        var items = [item(.commonIdentifierTitle, title)]
        if let subtitle { items.append(item(.iTunesMetadataTrackSubTitle, subtitle)) }
        return items
    }
    #endif
}

#if os(iOS)
extension AVPlayerEngine: AVPictureInPictureControllerDelegate {
    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController,
                                                restoreUserInterfaceForPictureInPictureStopWithCompletionHandler completionHandler: @escaping (Bool) -> Void) {
        Task { @MainActor in
            self.pipRestoring = true
            completionHandler(true)
        }
    }

    nonisolated func pictureInPictureControllerWillStartPictureInPicture(_ controller: AVPictureInPictureController) {
        MainActor.assumeIsolated { self.pipEngaged = true }
    }

    nonisolated func pictureInPictureController(_ controller: AVPictureInPictureController, failedToStartPictureInPictureWithError error: Error) {
        Task { @MainActor in self.pipEngaged = false }
    }

    nonisolated func pictureInPictureControllerDidStopPictureInPicture(_ controller: AVPictureInPictureController) {
        Task { @MainActor in
            self.pipEngaged = false
            // Closed with the "X" (no restore): the stream must not stay open in the background.
            if !self.pipRestoring { self.onPictureInPictureClosed?() }
            self.pipRestoring = false
        }
    }
}
#endif

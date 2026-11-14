import SwiftUI
import UIKit
import Shared

/// Raw failure reported by an engine; mapped to a user-facing `PlaybackError` by the view model.
struct EngineFailure {
    var message: String
    /// HTTP status from the AVPlayer error log (or CoreMedia error code mapping), if known.
    var httpStatus: Int?
    var nsError: NSError?
    /// The container could not be decoded by this engine.
    var unsupported = false
}

struct TrackOption: Identifiable, Hashable {
    let id: String
    let name: String
    let isSelected: Bool
}

/// Rendering + transport abstraction so the UI doesn't care which decoder is in use.
/// AVPlayer handles HLS/MP4 (with AirPlay & PiP); VLCKit (optional) handles MKV/AVI/TS.
@MainActor
protocol PlayerEngine: AnyObject {
    var view: UIView { get }
    var isPlaying: Bool { get }
    var isBuffering: Bool { get }
    /// Seconds.
    var currentTime: Double { get }
    /// Seconds, 0 when unknown or live.
    var duration: Double { get }
    var hasFailed: Bool { get }

    var onEnded: (() -> Void)? { get set }
    var onError: ((EngineFailure) -> Void)? { get set }
    /// The item is ready (tracks known): time for automatic track selection.
    var onReady: (() -> Void)? { get set }
    /// Playback stalled (rebuffering).
    var onStall: (() -> Void)? { get set }
    /// Playing / buffering state changed (lets the UI react before the next 1 s tick).
    var onStateChange: (() -> Void)? { get set }

    /// Applied on the next `load`.
    var bufferProfile: BufferProfile? { get set }
    var subtitleStyle: SubtitleStyle? { get set }
    /// Audio/subtitle delay applied on the next `load` (and live when supported).
    var supportsDelays: Bool { get }
    func setDelays(audioMs: Int64, subtitleMs: Int64)

    func load(_ stream: PlayableStream, startAt: Double, title: String, subtitle: String?)
    func play()
    func pause()
    func seek(to seconds: Double)
    /// Full teardown: stop, drop the item/media, cancel loading, remove observers. Idempotent. Never blocks.
    func stop()
    /// Like `stop()`; when the decoder closes asynchronously, returns a task that completes once the stream
    /// connection is really gone (the caller releases the connection lease only then). nil = already closed.
    func stopAndWait() -> Task<Void, Never>?

    /// Tracks in the shared `TrackInfo` form, for automatic selection via `Tracks.shared`.
    func trackInfos() async -> (audio: [TrackInfo], subtitles: [TrackInfo])
    /// Monospace lines for the stats overlay.
    func statsLines() -> [String]

    func audioTracks() async -> [TrackOption]
    func subtitleTracks() async -> [TrackOption]
    func selectAudio(_ id: String)
    func selectSubtitle(_ id: String?)
}

enum PlaybackURL {
    /// Kotlin returns "file://<path>" for downloads; the path may contain spaces ("Application Support").
    static func make(_ raw: String) -> URL? {
        if raw.hasPrefix("file://") { return URL(fileURLWithPath: String(raw.dropFirst("file://".count))) }
        return URL(string: raw) ?? URL(string: raw.addingPercentEncoding(withAllowedCharacters: .urlFragmentAllowed) ?? raw)
    }
}

enum EngineRouting {
    /// Containers AVFoundation can open natively.
    static let avContainers: Set<String> = ["m3u8", "mp4", "m4v", "mov", "m4a", "mp3", "aac", "3gp"]

    static func prefersVLC(_ container: String?) -> Bool {
        guard let c = container?.lowercased(), !c.isEmpty else { return false }
        return !avContainers.contains(c)
    }

    /// Xtream servers also serve live and catch-up as HLS: "/live/.../1.ts" → "/live/.../1.m3u8".
    static func hlsVariant(of stream: PlayableStream) -> PlayableStream? {
        let url = stream.url
        guard url.hasSuffix(".ts"), url.contains("/live/") || url.contains("/timeshift/") else { return nil }
        return PlayableStream(url: String(url.dropLast(3)) + ".m3u8", headers: stream.headers, isLive: stream.isLive, container: "m3u8")
    }
}

/// Hosts an engine's rendering view in SwiftUI.
struct EngineView: UIViewRepresentable {
    let engine: PlayerEngine
    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        embed(engine.view, in: container)
        return container
    }
    func updateUIView(_ uiView: UIView, context: Context) {
        if engine.view.superview !== uiView {
            uiView.subviews.forEach { $0.removeFromSuperview() }
            embed(engine.view, in: uiView)
        }
    }
    private func embed(_ view: UIView, in container: UIView) {
        view.frame = container.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(view)
    }
}

#if os(tvOS)
import AVKit
import CoreMedia

/// Frame-rate matching for engines that don't go through AVPlayerViewController (VLC).
/// Only takes effect if the user enabled "Adatta frequenza / gamma dinamica" in tvOS settings.
@MainActor
enum DisplayCriteria {
    static func apply(frameRate: Float?) {
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene }).first?.keyWindow else { return }
        let manager = window.avDisplayManager
        guard let fps = frameRate, fps > 0 else {
            manager.preferredDisplayCriteria = nil
            return
        }
        var format: CMVideoFormatDescription?
        CMVideoFormatDescriptionCreate(allocator: nil, codecType: kCMVideoCodecType_H264, width: 1920, height: 1080,
                                       extensions: nil, formatDescriptionOut: &format)
        guard let format else { return }
        manager.preferredDisplayCriteria = AVDisplayCriteria(refreshRate: fps, formatDescription: format)
    }

    static func reset() { apply(frameRate: nil) }
}
#endif

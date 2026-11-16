// Optional VLCKit engine for containers AVFoundation can't open (MKV, AVI, raw MPEG-TS, ...).
// Compiled only when a VLCKit module is linked:
//   - SPM:       https://github.com/videolan/vlckit (product "VLCKit", iOS + tvOS)  → module VLCKit
//   - CocoaPods: MobileVLCKit (iOS) / TVVLCKit (tvOS)
#if canImport(VLCKit)
import VLCKit
#elseif canImport(MobileVLCKit)
import MobileVLCKit
#elseif canImport(TVVLCKit)
import TVVLCKit
#endif
import UIKit
import Shared

enum VLCSupport {
    #if canImport(VLCKit) || canImport(MobileVLCKit) || canImport(TVVLCKit)
    static let isAvailable = true
    #else
    static let isAvailable = false
    #endif

    @MainActor
    static func makeEngine() -> PlayerEngine? {
        #if canImport(VLCKit) || canImport(MobileVLCKit) || canImport(TVVLCKit)
        return VLCPlayerEngine()
        #else
        return nil
        #endif
    }
}

#if canImport(VLCKit) || canImport(MobileVLCKit) || canImport(TVVLCKit)
/// VLCKit calls that can block for seconds (`stop`, releasing a playing player) run on this queue, never on main:
/// a network stream that doesn't answer would otherwise freeze the UI, and VLC's video output dispatches
/// synchronously to the main thread while closing (deadlock if main is waiting for `stop`).
private let vlcTeardownQueue = DispatchQueue(label: "steplayer.vlc-teardown", qos: .userInitiated)

@MainActor
final class VLCPlayerEngine: NSObject, PlayerEngine {
    /// One VLCMediaPlayer per opened stream; nil once stopped (the old one is torn down in the background).
    private var player: VLCMediaPlayer?
    /// Hosts one drawable subview per player, so a player that is still closing never draws into the next one.
    private let container = UIView()
    private var drawable: UIView?
    private var pendingStart: Double = 0
    private var endedReported = false

    var onEnded: (() -> Void)?
    var onError: ((EngineFailure) -> Void)?
    var onReady: (() -> Void)?
    var onStall: (() -> Void)?
    var onStateChange: (() -> Void)?
    var bufferProfile: BufferProfile?
    var subtitleStyle: SubtitleStyle?
    let supportsDelays = true
    private var audioDelayMs: Int64 = 0
    private var subtitleDelayMs: Int64 = 0

    func setDelays(audioMs: Int64, subtitleMs: Int64) {
        audioDelayMs = audioMs
        subtitleDelayMs = subtitleMs
        // VLC uses microseconds.
        player?.currentAudioPlaybackDelay = Int(audioMs * 1000)
        player?.currentVideoSubTitleDelay = Int(subtitleMs * 1000)
    }

    override init() {
        super.init()
        container.backgroundColor = .black
    }

    var view: UIView { container }
    /// The user's intent (play / pause). VLCKit 3 reports `.buffering` (and `isPlaying`) after a seek while
    /// paused: without this the paused player showed the spinner instead of the play button, with no way out.
    private var wantsToPlay = false
    var isPlaying: Bool { wantsToPlay && (player?.isPlaying ?? false) }
    /// VLCKit 3 keeps reporting `.buffering` while it is playing normally, so "buffering" means: opening, or
    /// buffering while the position hasn't moved for a moment (otherwise the spinner stays on over the video).
    var isBuffering: Bool {
        guard let player, wantsToPlay else { return false }
        let time = player.time.intValue
        if time != lastTime {
            lastTime = time
            lastAdvance = Date()
        }
        switch player.state {
        case .opening: return true
        case .buffering: return Date().timeIntervalSince(lastAdvance) > 1
        default: return false
        }
    }
    private var lastTime: Int32 = 0
    private var lastAdvance = Date.distantPast
    var hasFailed: Bool { player?.state == .error }
    var currentTime: Double {
        guard let player else { return 0 }
        let time = player.time.intValue
        // While paused VLC renders the new frame after a seek but keeps reporting the old time.
        if let seek = pendingSeek {
            if time == seek.from { return Double(seek.to) / 1000 }
            pendingSeek = nil
        }
        return Double(time) / 1000
    }
    private var pendingSeek: (from: Int32, to: Int32)?
    var duration: Double { Double(player?.media?.length.intValue ?? 0) / 1000 }

    func load(_ stream: PlayableStream, startAt: Double, title: String, subtitle: String?) {
        stop()
        guard let url = PlaybackURL.make(stream.url) else { onError?(EngineFailure(message: "Indirizzo dello stream non valido")); return }
        let media: VLCMedia? = VLCMedia(url: url)
        guard let media else { onError?(EngineFailure(message: "Impossibile aprire lo stream")); return }
        if let ua = stream.headers["User-Agent"] { media.addOption(":http-user-agent=\(ua)") }
        media.addOption(":network-caching=\(bufferProfile.map { Int($0.bufferForPlaybackAfterRebufferMs) } ?? 3000)")
        if let style = subtitleStyle {
            media.addOption(":freetype-rel-fontsize=\(Int(16 / max(0.5, Double(style.scale))))")
            media.addOption(":freetype-color=\(style.color == SubtitleColor.yellow ? 16776960 : 16777215)")
            let bg = style.background == SubtitleBackground.opaque ? 255 : (style.background == SubtitleBackground.semiTransparent ? 128 : 0)
            media.addOption(":freetype-background-opacity=\(bg)")
            media.addOption(":freetype-outline-thickness=\(style.edge == SubtitleEdge.outline ? 4 : 0)")
        }
        let player = VLCMediaPlayer()
        player.delegate = self
        player.media = media
        self.player = player
        pendingStart = startAt
        endedReported = false
        lastTime = 0
        lastAdvance = .distantPast
        pendingSeek = nil
        wantsToPlay = true
        player.currentAudioPlaybackDelay = Int(audioDelayMs * 1000)
        player.currentVideoSubTitleDelay = Int(subtitleDelayMs * 1000)
        // The connection lease is already held (tear down → release → acquire → open): only the drawable and
        // `play` wait for the layout, the order of the stream lifecycle is unchanged.
        Task { @MainActor [weak self] in
            await self?.layoutSettled()
            guard let self, self.player === player else { return } // stopped / replaced meanwhile
            self.begin(player, startAt: startAt)
        }
    }

    /// VLC's OpenGL view resets its buffers whenever its bounds change; if that happens while the screen is
    /// still rotating (player presented → landscape), VLCKit touches its layer off the main thread. So the
    /// drawable is attached once the container has its final size: in a window, non-empty, landscape on an
    /// iPhone, and unchanged for two frames. Never more than ~250 ms (a normal start waits 2–3 frames).
    private func layoutSettled() async {
        var last = CGSize.zero
        var stable = 0
        for _ in 0..<14 {
            let size = container.window == nil ? .zero : container.bounds.size
            var ready = size.width > 0 && size.height > 0
            #if os(iOS)
            if UIDevice.current.userInterfaceIdiom == .phone { ready = ready && size.width > size.height }
            #endif
            if ready && size == last { stable += 1 } else { stable = 0 }
            if stable >= 2 { return }
            last = size
            try? await Task.sleep(for: .milliseconds(16))
        }
    }

    private func begin(_ player: VLCMediaPlayer, startAt: Double) {
        let surface = UIView(frame: container.bounds)
        surface.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        surface.backgroundColor = .black
        container.addSubview(surface)
        player.drawable = surface
        drawable = surface
        if wantsToPlay { player.play() }
        if startAt > 1 {
            // VLC ignores seeks before the media is opened; retry shortly after.
            Task { @MainActor [weak self] in
                for _ in 0..<20 {
                    try? await Task.sleep(for: .milliseconds(250))
                    guard let self, self.player === player else { return }
                    if player.isPlaying { self.seek(to: self.pendingStart); self.pendingStart = 0; return }
                }
            }
        }
        #if os(tvOS)
        Task { @MainActor [weak self] in
            // Wait for the video track, then ask tvOS to match the display's refresh rate.
            for _ in 0..<20 {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, self.player === player else { return }
                if let fps = self.frameRate() { DisplayCriteria.apply(frameRate: fps); return }
            }
        }
        #endif
        Task { @MainActor [weak self] in
            // Signal readiness (tracks + delays) once playback has begun.
            for _ in 0..<40 {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, self.player === player else { return }
                if player.isPlaying {
                    self.setDelays(audioMs: self.audioDelayMs, subtitleMs: self.subtitleDelayMs)
                    self.onReady?()
                    return
                }
            }
        }
        // Lightweight watchdog for end/error (delegate signatures differ between VLCKit 3 and 4).
        Task { @MainActor [weak self] in
            var played = false
            var stoppedTicks = 0
            while true {
                try? await Task.sleep(for: .milliseconds(500))
                guard let self, self.player === player else { return }
                if player.state == .error {
                    self.onError?(EngineFailure(message: "Il server non risponde o lo stream non è disponibile"))
                    return
                }
                if player.time.intValue > 0 { played = true }
                let d = self.duration
                // VLC gives up on something it can't open (an HTML/JSON page instead of video, an empty answer)
                // by just stopping, without `.error`: a live stream that stops, or anything that never started,
                // is a failure (retry / error screen) instead of a black screen forever.
                if player.state == .stopped || player.state == .ended, !played || d <= 0 {
                    stoppedTicks += 1
                    if stoppedTicks >= 2 {
                        self.onError?(EngineFailure(message: "Il server non ha inviato un video riproducibile"))
                        return
                    }
                } else {
                    stoppedTicks = 0
                }
                if d > 0, !player.isPlaying, self.currentTime >= d - 1.5, !self.endedReported {
                    self.endedReported = true
                    self.onEnded?()
                }
            }
        }
    }

    func play() {
        wantsToPlay = true
        guard drawable != nil else { return } // not attached yet: `begin` starts it
        player?.play()
    }

    func pause() {
        wantsToPlay = false
        player?.pause()
    }

    func seek(to seconds: Double) {
        guard let player else { return }
        let target = Int32(max(0, seconds) * 1000)
        pendingSeek = (player.time.intValue, target)
        player.time = VLCTime(int: target)
    }

    func stop() { _ = stopAndWait() }

    /// Detaches the current player at once (no more callbacks, UI free) and stops/releases it on a background
    /// queue. The returned task finishes when VLC has really closed the stream (the provider connection is gone).
    @discardableResult
    func stopAndWait() -> Task<Void, Never>? {
        guard let old = player else { return nil }
        player = nil
        old.delegate = nil
        let surface = drawable
        drawable = nil
        #if os(tvOS)
        DisplayCriteria.reset()
        #endif
        let box = UncheckedBox(old)
        return Task { @MainActor in
            await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
                vlcTeardownQueue.async {
                    box.value.stop()
                    box.value.media = nil
                    cont.resume()
                }
            }
            // The last reference goes away here, after `stop` (releasing a stopped player is cheap).
            withExtendedLifetime(box) {}
            surface?.removeFromSuperview()
        }
    }

    /// Video frame rate when VLCKit exposes it (VLCKit 3 `tracksInformation`; VLCKit 4: TODO via `videoTracks`).
    private func frameRate() -> Float? {
        #if canImport(MobileVLCKit) || canImport(TVVLCKit)
        guard let tracks = player?.media?.tracksInformation as? [[String: Any]] else { return nil }
        for t in tracks where (t[VLCMediaTracksInformationType] as? String) == VLCMediaTracksInformationTypeVideo {
            if let num = (t[VLCMediaTracksInformationFrameRate] as? NSNumber)?.floatValue,
               let den = (t[VLCMediaTracksInformationFrameRateDenominator] as? NSNumber)?.floatValue, den > 0, num > 0 {
                return num / den
            }
        }
        return nil
        #else
        return nil
        #endif
    }

    func statsLines() -> [String] {
        [
            "Motore       VLC",
            "Stato        \(isPlaying ? "in riproduzione" : (isBuffering ? "buffering" : "in pausa"))",
            String(format: "Posizione    %.0f / %.0f s", currentTime, duration),
            "Profilo      \(bufferProfile?.name ?? "standard")",
        ]
    }

    // Track APIs differ between VLCKit 3 (index/name arrays) and 4 (track objects).
    #if canImport(MobileVLCKit) || canImport(TVVLCKit)
    func trackInfos() async -> (audio: [TrackInfo], subtitles: [TrackInfo]) {
        func map(_ names: [Any]?, _ ids: [Any]?) -> [TrackInfo] {
            let n = (names as? [String]) ?? []
            let i = (ids as? [NSNumber]) ?? []
            return zip(n, i).filter { $0.1.intValue >= 0 }.map {
                TrackInfo(id: $0.1.stringValue, language: nil, label: $0.0, channelCount: 0, isDefault: false, isForced: false)
            }
        }
        guard let player else { return ([], []) }
        return (map(player.audioTrackNames, player.audioTrackIndexes), map(player.videoSubTitlesNames, player.videoSubTitlesIndexes))
    }
    /// VLC reports only names ("Track 1 - [Italian]"): the shared rules read the language from them and keep one entry per language.
    private func list(_ infos: [TrackInfo], selected: Int32?) -> [TrackOption] {
        let selectedId = selected.map { String($0) }
        let kept = Tracks.shared.distinct(tracks: infos, selectedId: selectedId)
        return kept.enumerated().map { n, t in
            TrackOption(id: t.id, name: Tracks.shared.displayName(track: t, index: Int32(n)), isSelected: t.id == selectedId)
        }
    }
    func audioTracks() async -> [TrackOption] { list(await trackInfos().audio, selected: player?.currentAudioTrackIndex) }
    func subtitleTracks() async -> [TrackOption] { list(await trackInfos().subtitles, selected: player?.currentVideoSubTitleIndex) }
    func selectAudio(_ id: String) { if let i = Int32(id) { player?.currentAudioTrackIndex = i } }
    func selectSubtitle(_ id: String?) { player?.currentVideoSubTitleIndex = id.flatMap { Int32($0) } ?? -1 }
    #else
    // VLCKit 4: TODO map `audioTracks` / `textTracks` (VLCMediaPlayer.Track) to TrackInfo.
    func trackInfos() async -> (audio: [TrackInfo], subtitles: [TrackInfo]) { ([], []) }
    func audioTracks() async -> [TrackOption] { [] }
    func subtitleTracks() async -> [TrackOption] { [] }
    func selectAudio(_ id: String) {}
    func selectSubtitle(_ id: String?) {}
    #endif
}

#if canImport(MobileVLCKit) || canImport(TVVLCKit)
extension VLCPlayerEngine: VLCMediaPlayerDelegate {
    /// Playing / buffering changes reach the UI right away instead of on the next 1 s tick (spinner).
    nonisolated func mediaPlayerStateChanged(_ aNotification: Notification) {
        Task { @MainActor [weak self] in self?.onStateChange?() }
    }
}
#endif
#endif

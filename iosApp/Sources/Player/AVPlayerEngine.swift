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
    /// The current item has really played (time advanced): failures after that are network/server problems,
    /// before that AVFoundation may simply be unable to open this stream (the view model then tries VLC).
    private(set) var didPlay = false
    /// Opening timeout + the one-off "is the picture/sound really decoded?" check of the current item.
    private var checksTask: Task<Void, Never>?

    var onEnded: (() -> Void)?
    var onError: ((EngineFailure) -> Void)?
    var onReady: (() -> Void)?
    var onStall: (() -> Void)?
    var onStateChange: (() -> Void)?
    var bufferProfile: BufferProfile?
    var subtitleStyle: SubtitleStyle? {
        // Changed from the player's subtitle menu: restyle the current item live.
        didSet { if let s = subtitleStyle, let item = player.currentItem { item.textStyleRules = Self.textStyleRules(s) } }
    }
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
            Task { @MainActor in
                guard let self else { return }
                if self.player.timeControlStatus == .playing, self.player.currentItem != nil { self.didPlay = true }
                self.onStateChange?()
            }
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
        didPlay = false
        guard let url = PlaybackURL.make(stream.url) else {
            report(EngineFailure(message: "Indirizzo dello stream non valido"))
            return
        }
        var options: [String: Any] = [:]
        if !stream.headers.isEmpty { options["AVURLAssetHTTPHeaderFieldsKey"] = stream.headers }
        if let mime = EngineRouting.overrideMIME(for: stream, url: url) { options[AVURLAssetOverrideMIMETypeKey] = mime }
        let item = AVPlayerItem(asset: AVURLAsset(url: url, options: options.isEmpty ? nil : options))
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
        startChecks(for: item)
    }

    /// Two things AVPlayer doesn't report as errors:
    /// - a stream it can't parse sometimes never leaves `.unknown` (raw MPEG-TS over HTTP, odd servers):
    ///   after 10 s without becoming ready it counts as a failure, so VLC gets a chance at once;
    /// - a codec it can't decode inside a container it can open (HEVC tagged "hev1", MPEG-2 video, MP2/DTS
    ///   audio): it "plays" with a black picture or no sound. Once the clock has run for a few seconds the
    ///   picture must be on screen and at least one audio track must be decodable.
    private func startChecks(for item: AVPlayerItem) {
        checksTask?.cancel()
        checksTask = Task { @MainActor [weak self] in
            var waited = 0, played = 0
            // Ends once checked; a paused or buffering item simply isn't checked yet (no deadline).
            while true {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled, let self, self.player.currentItem === item, !self.failedReported else { return }
                if item.status == .unknown {
                    waited += 1
                    if waited >= 10 {
                        self.report(EngineFailure(message: "Lo stream non si apre", neverPlayed: true))
                        return
                    }
                    continue
                }
                guard item.status == .readyToPlay else { return } // .failed is reported by the observer
                if self.player.timeControlStatus == .playing { played += 1 }
                guard played >= 3, self.canVerifyDecoding else { continue } // 3 s of real playback, on screen
                if let problem = self.decodingProblem(item) {
                    self.report(EngineFailure(message: problem, unsupported: true))
                }
                return
            }
        }
    }

    /// In background / PiP / AirPlay the local layer legitimately shows nothing: check later.
    private var canVerifyDecoding: Bool {
        #if os(iOS)
        if isPictureInPictureActive { return false }
        #endif
        return UIApplication.shared.applicationState == .active && !player.isExternalPlaybackActive && layerView.window != nil
    }

    /// nil when picture and sound are really being decoded.
    private func decodingProblem(_ item: AVPlayerItem) -> String? {
        let tracks = item.tracks.filter(\.isEnabled).compactMap(\.assetTrack)
        let video = tracks.filter { $0.mediaType == .video }
        let audio = tracks.filter { $0.mediaType == .audio }
        if !video.isEmpty, !layerView.playerLayer.isReadyForDisplay || video.allSatisfy({ !$0.isPlayable }) {
            return "Video non decodificabile"
        }
        if !audio.isEmpty, audio.allSatisfy({ !$0.isPlayable }) {
            return "Audio non decodificabile"
        }
        return nil
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
            // VideoToolbox: bad / unsupported / broken video data.
            if e.domain == NSOSStatusErrorDomain, [-12909, -12910, -12911, -12906].contains(e.code) { unsupported = true }
            if e.domain == AVFoundationErrorDomain {
                // decodeFailed, fileFormatNotRecognized, fileFailedToParse, decoderNotFound,
                // operationNotSupportedForAsset, incompatibleAsset, serverIncorrectlyConfigured (no byte ranges).
                if [-11821, -11828, -11829, -11833, -11838, -11848, -11850].contains(e.code) { unsupported = true }
            }
        }
        let message = error?.localizedFailureReason ?? error?.localizedDescription ?? "Impossibile riprodurre questo contenuto"
        return EngineFailure(message: message, httpStatus: status, nsError: chain.first { $0.domain == NSURLErrorDomain } ?? error,
                             unsupported: unsupported, neverPlayed: !didPlay)
    }

    func play() { player.play() }
    func pause() { player.pause() }

    func seek(to seconds: Double) {
        player.seek(to: CMTime(seconds: max(0, seconds), preferredTimescale: 600),
                    toleranceBefore: CMTime(seconds: 0.5, preferredTimescale: 600),
                    toleranceAfter: CMTime(seconds: 0.5, preferredTimescale: 600))
    }

    func stop() {
        checksTask?.cancel()
        checksTask = nil
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
            options.enumerated().map { i, o in Self.info(i, o, group) }
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

    /// Shared description of a media option; accessibility characteristics feed the "non udenti" / audiodescrizione variants.
    private static func info(_ i: Int, _ o: AVMediaSelectionOption, _ group: AVMediaSelectionGroup?) -> TrackInfo {
        var label = o.displayName
        if o.hasMediaCharacteristic(.describesMusicAndSoundForAccessibility) || o.hasMediaCharacteristic(.transcribesSpokenDialogForAccessibility) { label += " SDH" }
        if o.hasMediaCharacteristic(.describesVideoForAccessibility) { label += " audio description" }
        return TrackInfo(id: String(i), language: o.extendedLanguageTag ?? o.locale?.identifier, label: label,
                         channelCount: 0, isDefault: o == group?.defaultOption,
                         isForced: o.hasMediaCharacteristic(.containsOnlyForcedSubtitles))
    }

    /// One entry per language (shared rules: the track in use, then the default, stays), named the same way on every platform.
    private func list(_ characteristic: AVMediaCharacteristic) async -> [TrackOption] {
        guard let (group, options) = await options(for: characteristic), let item = player.currentItem else { return [] }
        let selected = item.currentMediaSelection.selectedMediaOption(in: group)
        let infos = options.enumerated().map { i, o in Self.info(i, o, group) }
        let selectedId = options.firstIndex { $0 == selected }.map(String.init)
        let kept = Tracks.shared.distinct(tracks: infos, selectedId: selectedId)
        return kept.enumerated().map { n, t in
            TrackOption(id: t.id, name: Tracks.shared.displayName(track: t, index: Int32(n)), isSelected: t.id == selectedId)
        }
    }

    func audioTracks() async -> [TrackOption] { await list(.audible) }

    func subtitleTracks() async -> [TrackOption] { await list(.legible) }

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

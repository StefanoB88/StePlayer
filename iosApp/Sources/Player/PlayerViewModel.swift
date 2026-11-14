import AVFoundation
import Foundation
import Observation
import Shared
import UIKit

/// Drives playback with strict connection hygiene:
/// every start/zap/retry = tear down → release lease → acquire lease → open. Never two streams at once.
@MainActor
@Observable
final class PlayerViewModel {
    private(set) var item: PlayItem
    private(set) var engine: PlayerEngine?
    private(set) var engineGeneration = 0

    var isPlaying = false
    private(set) var isBuffering = true
    /// The loading spinner: shown only when buffering lasts more than 300 ms (no flicker on fast starts/zaps).
    private(set) var spinnerVisible = false
    var currentTime: Double = 0
    var duration: Double = 0
    var nowProgram: EpgProgram?
    var nextProgram: EpgProgram?
    var audioTracks: [TrackOption] = []
    var subtitleTracks: [TrackOption] = []
    var nextEpisodeCountdown: Int?
    var shouldDismiss = false

    /// Final error (retry exhausted / not retryable).
    var failure: PlaybackError?
    /// Seconds until the automatic retry, while one is pending.
    var retryCountdown: Int?
    /// The account's connections are all used by downloads.
    var busy: AcquireResultDenied?
    /// Zapping UI.
    var miniGuideVisible = false
    var showChannelList = false
    var lastChannelName: String?

    /// "Stai ancora guardando?" countdown (seconds) while the prompt is shown.
    var stillWatchingCountdown: Int?
    /// Sleep-timer warning countdown (seconds).
    var sleepWarning: Int?
    var sleepRemainingText: String?
    /// Why the player stopped on its own (inactivity / sleep timer), shown after dismissal.
    var stopMessage: String?
    var showStats = false
    var statsLines: [String] = []
    /// Technical code of the last engine failure ("CoreMediaErrorDomain -12646"): stats overlay only.
    @ObservationIgnored private var lastErrorCode: String?
    var bufferBanner = false
    var audioDelayMs: Int64 = 0
    var subtitleDelayMs: Int64 = 0
    var delayNotice: String?
    var showSyncPanel = false
    var showConnectionTest = false
    private(set) var settings: PlayerSettings?

    @ObservationIgnored private let core: StePlayerCore
    @ObservationIgnored private var timer: Timer?
    @ObservationIgnored private var lastSave = Date.distantPast
    @ObservationIgnored private var lastEpgReload = Date.distantPast
    @ObservationIgnored private var currentStream: PlayableStream?
    @ObservationIgnored private var forceVLC = false
    @ObservationIgnored private var countdownTask: Task<Void, Never>?
    @ObservationIgnored private var retryTask: Task<Void, Never>?
    @ObservationIgnored private var zapTask: Task<Void, Never>?
    @ObservationIgnored private var startToken = 0
    @ObservationIgnored private var resumeAt: Double?
    @ObservationIgnored private var healthySince: Date?
    @ObservationIgnored private var userPaused = false
    /// Paused by the system (phone call, Siri, alarm): resumes by itself when the interruption allows it.
    @ObservationIgnored private var systemPaused = false
    @ObservationIgnored private var resumeAfterInterruption = false
    /// Between `start()` and the engine opening the stream (resolve, lease, probe): counts as buffering.
    @ObservationIgnored private var isStarting = false
    @ObservationIgnored private var spinnerTask: Task<Void, Never>?
    /// Completes when the previous engine has really closed its stream and its lease was released.
    @ObservationIgnored private var teardownDone: Task<Void, Never>?
    @ObservationIgnored private var isClosed = false
    @ObservationIgnored private var suspendedInBackground = false
    @ObservationIgnored private var lifecycleObservers: [NSObjectProtocol] = []
    @ObservationIgnored private var zapper: ChannelZapper?
    @ObservationIgnored private var miniGuideTask: Task<Void, Never>?
    @ObservationIgnored private var epgSub: FlowSubscription?
    @ObservationIgnored private var settingsSub: FlowSubscription?
    @ObservationIgnored private var session: WatchSessionTimer?
    @ObservationIgnored private let advisorFor: (BufferMode) -> BufferAdvisor
    @ObservationIgnored private var manualAudio = false
    @ObservationIgnored private var manualSubtitle = false
    @ObservationIgnored private var bannerTask: Task<Void, Never>?
    @ObservationIgnored private var delaysKey: String?
    @ObservationIgnored private lazy var retryPolicy = core.playback.retryPolicy(isLive: isLive)
    @ObservationIgnored private lazy var watchdog = core.playback.stallWatchdog(isLive: isLive)
    /// Accessed from deinit as a last-resort safety net.
    @ObservationIgnored nonisolated(unsafe) private var lease: ConnectionLease?

    /// `startAt`: explicit start position in seconds ("Dall'inizio" = 0); nil resumes from saved progress.
    init(item: PlayItem, core: StePlayerCore, startAt: Double? = nil, advisorFor: @escaping (BufferMode) -> BufferAdvisor) {
        self.item = item
        self.core = core
        self.advisorFor = advisorFor
        self.resumeAt = startAt
        settingsSub = observe(core.playerPreferences.settings) { [weak self] (s: PlayerSettings) in
            self?.settings = s
            if self?.showStats == false, s.showStatsOverlay { self?.showStats = true }
        }
        if case let .live(c, list) = item {
            zapper = ChannelZapper(channels: list.isEmpty ? [c] : list, start: c, numberEntryTimeoutMs: 2000, maxDigits: 4)
            subscribeNowNext(c)
        }
        observeLifecycle()
        observeAudioSession()
    }

    deinit {
        lease?.release()
    }

    // MARK: Derived

    var isLive: Bool { if case .live = item { return true } else { return false } }

    var title: String {
        switch item {
        case let .live(c, _): return c.name
        case let .catchup(_, p): return p.title
        case let .movie(m): return m.name
        case let .episode(s, _, _): return s.name
        }
    }

    var subtitle: String? {
        switch item {
        case .live: return nowProgram?.title
        case let .catchup(c, p): return "\(c.name) · \(Fmt.dayTime(p.startAt))"
        case let .movie(m): return m.year.map { String($0.int32Value) }
        case let .episode(_, e, _): return "S\(e.season) · E\(e.number) — \(e.title)"
        }
    }

    var channel: Channel? {
        if case let .live(c, _) = item { return c }
        if case let .catchup(c, _) = item { return c }
        return nil
    }

    var zapList: [Channel] { if case let .live(_, list) = item { return list } else { return [] } }

    var sourceId: Int64 {
        switch item {
        case let .live(c, _), let .catchup(c, _): return c.sourceId
        case let .movie(m): return m.sourceId
        case let .episode(_, e, _): return e.sourceId
        }
    }

    var nextEpisode: Episode? {
        guard case let .episode(_, e, all) = item, let i = all.firstIndex(where: { $0.id == e.id }), i + 1 < all.count else { return nil }
        return all[i + 1]
    }

    // MARK: Start / teardown

    /// Opens the current item. Safe to call repeatedly: cancels any in-flight start.
    func start() async {
        guard !isClosed else { return }
        cancelPendingRetry()
        startToken += 1
        let token = startToken
        teardown()
        failure = nil
        busy = nil
        isStarting = true
        setBuffering(true)
        userPaused = false
        systemPaused = false
        nextEpisodeCountdown = nil
        countdownTask?.cancel()
        startTimer()
        defer { if token == startToken { isStarting = false } }

        do {
            if settings == nil { settings = try? await core.playerPreferences.current() }
            if session == nil {
                let timer = WatchSessionTimer(stillWatchingAfterHours: settings?.stillWatchingAfterHours ?? 4,
                                              promptCountdownMs: 60_000, sleepWarningMs: 60_000)
                timer.start(nowMs: .nowMs)
                session = timer
            }
            await loadDelays()
            let stream = try await resolve()
            guard token == startToken, !isClosed else { return }
            let isLocal = stream.url.hasPrefix("file://")
            // Rule: the previous stream must be really closed (and its lease released) before a new acquire.
            if let pending = teardownDone { await pending.value }
            guard token == startToken, !isClosed else { return }
            if !isLocal {
                let result = try await core.playback.connections.acquirePlayback(sourceId: sourceId, label: title) { [weak self] in
                    Self.onMain { self?.revokedByAnotherPlayer() }
                }
                if let granted = result as? AcquireResultGranted {
                    guard token == startToken, !isClosed else { granted.lease.release(); return }
                    lease = granted.lease
                } else if let denied = result as? AcquireResultDenied {
                    guard token == startToken else { return }
                    busy = denied
                    isStarting = false
                    setBuffering(false)
                    return
                }
            }
            // VOD only: look at the first bytes (after the lease: the probe is a provider connection too)
            // to learn the real container, or catch an error page sent instead of the video.
            if isVod && !isLocal, let url = PlaybackURL.make(stream.url),
               let probe = await StreamProbe.probe(url: url, headers: stream.headers) {
                guard token == startToken, !isClosed else { releaseLease(); return }
                if case .serverError = probe.kind {
                    await failForServerErrorPage(token: token)
                    return
                }
                if let container = StreamProbe.container(for: probe.kind), ["mkv", "avi", "flv", "ts"].contains(container),
                   VLCSupport.isAvailable {
                    forceVLC = true
                }
            }
            let startAt: Double
            if let r = resumeAt { startAt = r } else { startAt = await savedPosition() }
            guard token == startToken, !isClosed else { releaseLease(); return }
            open(stream, startAt: startAt)
            isStarting = false
            syncEngineState()
            if case let .live(c, _) = item {
                _ = try? await core.library.saveProgress(sourceId: c.sourceId, type: .live, itemId: c.id, parentId: nil, title: c.name,
                                                         subtitle: nil, image: c.logo, positionMs: 0, durationMs: 0)
                if nowProgram == nil { await loadEpg(for: c) } // per-channel API fallback when the XMLTV has nothing
            }
        } catch {
            guard token == startToken else { return }
            teardown()
            PlaybackErrorText.record(PlaybackErrorText.code(error as NSError), context: "start failed")
            failure = PlaybackErrorText.startError(error)
            isStarting = false
            setBuffering(false)
        }
    }

    private var isVod: Bool {
        switch item {
        case .movie, .episode: return true
        default: return false
        }
    }

    /// The provider answered with an HTML/JSON/text page instead of the video: same path as a playback failure
    /// (tear down and free the connection first, then ask the server what's wrong).
    private func failForServerErrorPage(token: Int) async {
        teardown()
        let diagnosed = try? await core.playback.diagnose(sourceId: sourceId)
        guard token == startToken, !isClosed else { return }
        scheduleRetryOrFail(diagnosed ?? PlaybackError(
            kind: .unknown,
            title: "Il provider non ha inviato il video",
            message: "Il server ha risposto con un errore invece del contenuto. Riprova più tardi o verifica l'abbonamento.",
            retryable: true))
    }

    /// Stops decoding and frees the provider connection. Idempotent, never blocks the main thread.
    /// When the engine closes asynchronously (VLC), the lease is released once the stream is really gone
    /// (at most 5 s later) and the next `start()` waits for that before acquiring a new one.
    private func teardown() {
        let pending = engine?.stopAndWait()
        if let pending, let lease {
            self.lease = nil
            teardownDone = Task {
                await Self.waitAtMost(pending, seconds: 5)
                lease.release()
            }
        } else {
            releaseLease()
        }
        watchdog.reset()
        healthySince = nil
    }

    private static func waitAtMost(_ task: Task<Void, Never>, seconds: Double) async {
        let once = OnceFlag()
        await withCheckedContinuation { (cont: CheckedContinuation<Void, Never>) in
            Task { await task.value; if once.fire() { cont.resume() } }
            Task { try? await Task.sleep(for: .seconds(seconds)); if once.fire() { cont.resume() } }
        }
    }

    /// Buffering state + delayed spinner.
    private func setBuffering(_ value: Bool) {
        if isBuffering != value { isBuffering = value }
        if value {
            guard !spinnerVisible, spinnerTask == nil else { return }
            spinnerTask = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(300))
                guard let self, !Task.isCancelled else { return }
                self.spinnerTask = nil
                if self.isBuffering && !self.spinnerVisible { self.spinnerVisible = true }
            }
        } else {
            spinnerTask?.cancel()
            spinnerTask = nil
            if spinnerVisible { spinnerVisible = false }
        }
    }

    /// Mirrors the engine's playing/buffering state (1 s tick, and immediately on engine state changes).
    private func syncEngineState() {
        guard let engine, !isClosed else { return }
        let playing = engine.isPlaying
        if isPlaying != playing { isPlaying = playing }
        setBuffering(isStarting || (isStreaming && engine.isBuffering))
    }

    /// A stream is open and healthy-or-loading (not failed, not waiting for a retry).
    private var isStreaming: Bool {
        !isStarting && (lease != nil || currentStream?.url.hasPrefix("file://") == true) && failure == nil && retryCountdown == nil
    }

    private func releaseLease() {
        lease?.release()
        lease = nil
    }

    /// User closed the player (or it was dismissed). Idempotent.
    func close() {
        guard !isClosed else { return }
        saveProgress()
        isClosed = true
        startToken += 1
        cancelPendingRetry()
        zapTask?.cancel()
        miniGuideTask?.cancel()
        bannerTask?.cancel()
        epgSub = nil
        settingsSub = nil
        countdownTask?.cancel()
        spinnerTask?.cancel()
        timer?.invalidate()
        timer = nil
        teardown()
        lifecycleObservers.forEach { NotificationCenter.default.removeObserver($0) }
        lifecycleObservers = []
    }

    private func revokedByAnotherPlayer() {
        // Another playback took the connection: shut down synchronously and leave.
        lease = nil
        engine?.stop()
        close()
        shouldDismiss = true
    }

    private func resolve() async throws -> PlayableStream {
        switch item {
        case let .live(c, _): return try await core.streams.live(channel: c)
        case let .catchup(c, p): return try await core.streams.catchup(channel: c, program: p)
        case let .movie(m): return try await core.streams.movie(movie: m)
        case let .episode(_, e, _): return try await core.streams.episode(episode: e)
        }
    }

    private func savedPosition() async -> Double {
        let lookup: (Int64, ContentType, String)?
        switch item {
        case let .movie(m): lookup = (m.sourceId, .movie, m.id)
        case let .episode(_, e, _): lookup = (e.sourceId, .episode, e.id)
        default: lookup = nil
        }
        guard let (sid, type, id) = lookup,
              let p = try? await core.library.progress(sourceId: sid, type: type, itemId: id),
              p.positionMs > 30_000, p.fraction < 0.95 else { return 0 }
        return Double(p.positionMs) / 1000
    }

    private func open(_ stream: PlayableStream, startAt: Double) {
        currentStream = stream
        let needsDelays = audioDelayMs != 0 || subtitleDelayMs != 0
        var wantsVLC = forceVLC || EngineRouting.prefersVLC(stream.container) || (needsDelays && VLCSupport.isAvailable)
        #if DEBUG
        // Development only: `STEPLAYER_DEMO_FORCE_VLC=1` proves the VLC engine on the simulator.
        if ProcessInfo.processInfo.environment["STEPLAYER_DEMO_FORCE_VLC"] == "1" { wantsVLC = true }
        #endif
        let newEngine: PlayerEngine
        var s = stream
        if wantsVLC, let vlc = VLCSupport.makeEngine() {
            newEngine = vlc
        } else {
            if wantsVLC { s = EngineRouting.hlsVariant(of: stream) ?? stream }
            newEngine = AVPlayerEngine()
        }
        // Callbacks from an engine that was already replaced (fast zapping, retries) are ignored.
        engineGeneration += 1
        let gen = engineGeneration
        newEngine.onError = { [weak self] f in
            guard let self, self.engineGeneration == gen, !self.isClosed else { return }
            self.handleFailure(f)
        }
        newEngine.onEnded = { [weak self] in
            guard let self, self.engineGeneration == gen, !self.isClosed else { return }
            self.handleEnded()
        }
        newEngine.onReady = { [weak self] in
            guard let self, self.engineGeneration == gen else { return }
            Task { await self.autoSelectTracks() }
        }
        newEngine.onStall = { [weak self] in
            guard let self, self.engineGeneration == gen else { return }
            self.handleBufferStall()
        }
        newEngine.onStateChange = { [weak self] in
            guard let self, self.engineGeneration == gen else { return }
            self.syncEngineState()
        }
        let advisor = advisorFor(settings?.bufferMode ?? .large)
        newEngine.bufferProfile = advisor.profile()
        newEngine.subtitleStyle = settings?.subtitleStyle
        if newEngine.supportsDelays { newEngine.setDelays(audioMs: audioDelayMs, subtitleMs: subtitleDelayMs) }
        #if os(iOS)
        (newEngine as? AVPlayerEngine)?.onPictureInPictureClosed = { [weak self] in
            guard let self, self.engineGeneration == gen else { return }
            self.close()
            self.shouldDismiss = true
        }
        #endif
        engine = newEngine
        newEngine.load(s, startAt: startAt, title: title, subtitle: subtitle)
    }

    // MARK: Failures & retry

    private func handleFailure(_ f: EngineFailure) {
        // Rule: tear down and free the connection BEFORE anything else.
        let position = currentTime
        if !isLive && position > 1 { resumeAt = position }
        teardown()
        let token = startToken

        // AVPlayer can't decode the container: switch to VLC right away if available.
        if f.unsupported, !forceVLC, VLCSupport.isAvailable, engine is AVPlayerEngine {
            forceVLC = true
            Task { await start() }
            return
        }

        Task {
            let error = await mapError(f)
            guard token == startToken, !isClosed else { return }
            scheduleRetryOrFail(error)
        }
    }

    private func mapError(_ f: EngineFailure) async -> PlaybackError {
        if let status = f.httpStatus, let e = try? await core.playback.errorForHttpStatus(sourceId: sourceId, status: Int32(status)) {
            return e
        }
        if f.unsupported {
            return PlaybackErrors.shared.unsupported(container: currentStream?.container)
        }
        // The user reads plain Italian; the raw domain/code goes to the log and the stats overlay only.
        let code = PlaybackErrorText.code(f.nsError)
        PlaybackErrorText.record(code, context: "engine failed")
        if let code { lastErrorCode = code }
        var mapped = PlaybackErrorText.playbackError(nsError: f.nsError, message: f.message, container: currentStream?.container)
        // Ambiguous: ask the server (Xtream) what's going on (expired, max connections, ...).
        if mapped.kind == PlaybackErrorKind.unknown || mapped.kind == PlaybackErrorKind.network {
            if let diagnosed = try? await core.playback.diagnose(sourceId: sourceId) { mapped = diagnosed }
        }
        return mapped
    }

    private func scheduleRetryOrFail(_ error: PlaybackError) {
        cancelPendingRetry()
        guard let delay = retryPolicy.nextDelayMs(error: error)?.int64Value else {
            failure = error
            setBuffering(false)
            return
        }
        failure = nil
        setBuffering(false)
        var seconds = max(1, Int((delay + 999) / 1000))
        retryCountdown = seconds
        retryTask = Task { [weak self] in
            while seconds > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                seconds -= 1
                self?.retryCountdown = seconds
            }
            guard let self, !Task.isCancelled else { return }
            self.retryCountdown = nil
            self.lastRetryError = error
            await self.startForRetry()
        }
        lastRetryError = error
    }

    @ObservationIgnored private var lastRetryError: PlaybackError?

    private func startForRetry() async {
        // Keep the retry budget across this automatic attempt.
        await start()
    }

    /// User tapped "Annulla" during the retry countdown.
    func cancelRetry() {
        cancelPendingRetry()
        failure = lastRetryError ?? PlaybackErrors.shared.network()
    }

    private func cancelPendingRetry() {
        retryTask?.cancel()
        retryTask = nil
        retryCountdown = nil
    }

    /// User tapped "Riprova".
    func retry() {
        retryPolicy.reset()
        Task { await start() }
    }

    /// "Sospendi i download e guarda".
    func pauseDownloadsAndPlay() {
        busy = nil
        Task {
            try? await core.downloads.pauseAll()
            await start()
        }
    }

    private func handleEnded() {
        saveProgress(finished: true)
        if nextEpisode != nil {
            startNextEpisodeCountdown()
        } else if !isLive {
            teardown()
            shouldDismiss = true
        }
    }

    // MARK: Transport

    func togglePlay() {
        interaction()
        guard let engine else { return }
        if engine.isPlaying {
            engine.pause()
            userPaused = true
        } else {
            engine.play()
            userPaused = false
            systemPaused = false
            watchdog.reset()
        }
        isPlaying.toggle()
    }

    func seek(by delta: Double) { seek(to: currentTime + delta) }

    func seek(to seconds: Double) {
        interaction()
        let target = duration > 0 ? min(max(0, seconds), duration - 1) : max(0, seconds)
        engine?.seek(to: target)
        currentTime = target
        watchdog.reset()
    }

    /// Next/previous channel in the current list. The overlay updates instantly; the stream opens 500 ms after the last zap.
    func zap(_ delta: Int) {
        guard let zapper else { return }
        apply(delta > 0 ? zapper.next() : zapper.previous())
    }

    /// Jumps back to the previously watched channel.
    func back() {
        guard let ch = zapper?.back() else { return }
        apply(ch)
    }

    func switchTo(_ channel: Channel) {
        guard let zapper else { return }
        apply(zapper.select(channel: channel))
    }

    /// "Vai al numero": looks in the current list first, then the whole list.
    func goToNumber(_ number: Int) async -> Bool {
        guard let zapper else { return false }
        var target = zapper.channelForNumber(number: Int32(number))
        if target == nil {
            target = try? await core.catalog.channelByNumber(sourceId: sourceId, number: Int32(number))
        }
        guard let ch = target else { return false }
        apply(zapper.select(channel: ch))
        return true
    }

    private func apply(_ channel: Channel) {
        interaction()
        guard case let .live(current, list) = item else { return }
        lastChannelName = zapper?.lastChannel?.name
        guard channel.id != current.id else { showMiniGuide(); return }
        item = .live(channel, zapList: list)
        nowProgram = nil
        nextProgram = nil
        subscribeNowNext(channel)
        showMiniGuide()
        cancelPendingRetry()
        retryPolicy.reset()
        forceVLC = false
        resumeAt = nil
        zapTask?.cancel()
        zapTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard let self, !Task.isCancelled else { return }
            await self.start()
        }
    }

    func showMiniGuide() {
        miniGuideVisible = true
        miniGuideTask?.cancel()
        miniGuideTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(4))
            guard !Task.isCancelled else { return }
            self?.miniGuideVisible = false
        }
    }

    private func subscribeNowNext(_ channel: Channel) {
        epgSub = observeList(core.epg.nowNext(sourceId: channel.sourceId, channels: [channel], now: .nowMs), as: ChannelWithEpg.self) { [weak self] rows in
            guard let self, self.channel?.id == channel.id, let row = rows.first else { return }
            if let now = row.now { self.nowProgram = now }
            if let next = row.next { self.nextProgram = next }
        }
    }

    func playNextEpisode() {
        countdownTask?.cancel()
        nextEpisodeCountdown = nil
        guard case let .episode(s, _, all) = item, let next = nextEpisode else { return }
        saveProgress(finished: true)
        item = .episode(s, next, all: all)
        resumeAt = nil
        forceVLC = false
        retryPolicy.reset()
        Task { await start() }
    }

    func cancelNextEpisode() {
        countdownTask?.cancel()
        nextEpisodeCountdown = nil
    }

    private func startNextEpisodeCountdown() {
        guard nextEpisodeCountdown == nil else { return }
        countdownTask?.cancel()
        nextEpisodeCountdown = 8
        countdownTask = Task { [weak self] in
            while let self, let n = self.nextEpisodeCountdown, n > 0 {
                try? await Task.sleep(for: .seconds(1))
                if Task.isCancelled { return }
                self.nextEpisodeCountdown = n - 1
            }
            if Task.isCancelled { return }
            self?.playNextEpisode()
        }
    }

    // MARK: Tracks

    func refreshTracks() async {
        guard let engine else { return }
        audioTracks = await engine.audioTracks()
        subtitleTracks = await engine.subtitleTracks()
    }

    func selectAudio(_ t: TrackOption) {
        manualAudio = true
        interaction()
        engine?.selectAudio(t.id)
        Task { try? await Task.sleep(for: .milliseconds(300)); await refreshTracks() }
    }

    func selectSubtitle(_ t: TrackOption?) {
        manualSubtitle = true
        interaction()
        engine?.selectSubtitle(t?.id)
        Task { try? await Task.sleep(for: .milliseconds(300)); await refreshTracks() }
    }

    // MARK: Ticking (1 s)

    private func startTimer() {
        guard timer == nil else { return }
        // `.common` mode: keeps ticking (position, stall watchdog) while a list in the player is being scrolled.
        let t = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.tick() }
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
    }

    private func tick() {
        guard let engine, !isClosed else { return }
        let streaming = isStreaming
        syncEngineState()
        let t = engine.currentTime
        if currentTime != t { currentTime = t }
        let d = engine.duration
        if duration != d { duration = d }

        if streaming {
            let wantsToPlay = !userPaused && !systemPaused
            if watchdog.tick(nowMs: .nowMs, positionMs: Int64(currentTime * 1000), wantsToPlay: wantsToPlay) {
                handleStall()
                return
            }
            if engine.isPlaying {
                if healthySince == nil { healthySince = Date() }
                if let since = healthySince, Date().timeIntervalSince(since) > 30 {
                    retryPolicy.reset()
                    healthySince = .distantFuture
                }
            } else if healthySince != .distantFuture {
                healthySince = nil
            }
        }

        if engine.isPlaying || stillWatchingCountdown != nil || sleepWarning != nil {
            handleSession()
        }
        if showStats {
            var lines = engine.statsLines()
            if let code = lastErrorCode { lines.append("Ultimo errore \(code)") }
            if lines != statsLines { statsLines = lines }
        }

        if !isLive && Date().timeIntervalSince(lastSave) > 10 && currentTime > 5 {
            saveProgress()
        }
        if !isLive && currentTime > 1 { resumeAt = currentTime }
        if nextEpisode != nil, duration > 120, duration - currentTime < 25, nextEpisodeCountdown == nil, isPlaying {
            startNextEpisodeCountdown()
        }
        if isLive, let c = channel, let now = nowProgram, now.endAt < Int64.nowMs, Date().timeIntervalSince(lastEpgReload) > 30 {
            lastEpgReload = Date() // at most every 30 s while the guide has nothing newer
            Task { await loadEpg(for: c) }
        }
    }

    private func handleStall() {
        if !isLive && currentTime > 1 { resumeAt = currentTime }
        teardown()
        scheduleRetryOrFail(PlaybackErrors.shared.stalled())
    }

    private func saveProgress(finished: Bool = false) {
        guard !isClosed else { return }
        lastSave = Date()
        let pos = Int64((finished ? duration : currentTime) * 1000)
        let dur = Int64(duration * 1000)
        guard pos > 0 else { return }
        switch item {
        case let .movie(m):
            Task { _ = try? await core.library.saveProgress(sourceId: m.sourceId, type: .movie, itemId: m.id, parentId: nil, title: m.name,
                                                            subtitle: m.year.map { String($0.int32Value) }, image: m.poster,
                                                            positionMs: pos, durationMs: dur) }
        case let .episode(s, e, _):
            Task { _ = try? await core.library.saveProgress(sourceId: e.sourceId, type: .episode, itemId: e.id, parentId: s.id, title: s.name,
                                                            subtitle: "S\(e.season)·E\(e.number) — \(e.title)", image: e.image ?? s.cover,
                                                            positionMs: pos, durationMs: dur) }
        default:
            break
        }
    }

    private func loadEpg(for channel: Channel) async {
        let now = Int64.nowMs
        let list = (try? await core.epg.programs(channel: channel, from: now - 3600_000, to: now + 6 * 3600_000)) ?? []
        guard self.channel?.id == channel.id else { return }
        nowProgram = list.first { $0.isLive(now: now) }
        nextProgram = list.first { $0.startAt >= (nowProgram?.endAt ?? now) }
    }

    // MARK: Session timer ("Stai ancora guardando?" + sleep timer)

    /// Every touch, remote press, zap or menu action.
    func interaction() {
        session?.onInteraction(nowMs: .nowMs)
        if stillWatchingCountdown != nil { stillWatchingCountdown = nil }
    }

    private func handleSession() {
        guard let session else { return }
        let now = Int64.nowMs
        let state = session.tick(nowMs: now)
        if let ask = state as? SessionStateAskStillWatching {
            stillWatchingCountdown = Int(ask.secondsLeft)
            sleepWarning = nil
        } else if let warn = state as? SessionStateSleepWarning {
            sleepWarning = Int(warn.secondsLeft)
            stillWatchingCountdown = nil
        } else if let stop = state as? SessionStateStop {
            stopMessage = stop.reason == StopReason.inactivity
                ? "Riproduzione interrotta per inattività"
                : "Timer di spegnimento terminato"
            stillWatchingCountdown = nil
            sleepWarning = nil
            close()
            shouldDismiss = true
            return
        } else {
            stillWatchingCountdown = nil
            sleepWarning = nil
        }
        if let ms = session.sleepRemainingMs(nowMs: now)?.int64Value {
            sleepRemainingText = "Spegnimento tra \(Fmt.duration(seconds: Int(ms / 1000)))"
        } else {
            sleepRemainingText = nil
        }
    }

    func continueWatching() { interaction() }

    func exitFromPrompt() {
        close()
        shouldDismiss = true
    }

    /// nil = off.
    func setSleepTimer(minutes: Int?) {
        interaction()
        if let minutes { session?.setSleepTimer(minutes: Int32(minutes), nowMs: .nowMs) } else { session?.cancelSleep() }
        sleepWarning = nil
        handleSession()
    }

    var canSleepAtProgramEnd: Bool { isLive && nowProgram != nil }

    func sleepAtProgramEnd() {
        interaction()
        guard let end = nowProgram?.endAt else { return }
        session?.setSleepAt(epochMs: end)
        handleSession()
    }

    func extendSleep() {
        session?.extendSleep(minutes: 15, nowMs: .nowMs)
        sleepWarning = nil
        interaction()
    }

    func cancelSleep() {
        session?.cancelSleep()
        sleepWarning = nil
        sleepRemainingText = nil
        interaction()
    }

    // MARK: Buffer advisor

    private func handleBufferStall() {
        let advisor = advisorFor(settings?.bufferMode ?? .large)
        if advisor.onStall(nowMs: .nowMs) {
            // Applied on the next start/retry.
            bufferBanner = true
            bannerTask?.cancel()
            bannerTask = Task { [weak self] in
                try? await Task.sleep(for: .seconds(5))
                guard !Task.isCancelled else { return }
                self?.bufferBanner = false
            }
        }
    }

    // MARK: Tracks (automatic selection)

    private func autoSelectTracks() async {
        guard let engine else { return }
        if let settings, !(manualAudio && manualSubtitle) {
            let (audio, subs) = await engine.trackInfos()
            let languages = Locale.preferredLanguages
            var chosenAudio = audio.first(where: \.isDefault)
            if !manualAudio, let pick = Tracks.shared.pickAudio(tracks: audio, settings: settings, deviceLanguages: languages) {
                engine.selectAudio(pick.id)
                chosenAudio = pick
            }
            if !manualSubtitle, !subs.isEmpty {
                let pick = Tracks.shared.pickSubtitle(tracks: subs, selectedAudio: chosenAudio, settings: settings, deviceLanguages: languages)
                engine.selectSubtitle(pick?.id)
            }
            try? await Task.sleep(for: .milliseconds(300))
        }
        await refreshTracks()
    }

    // MARK: Audio / subtitle sync

    private var contentKey: String {
        switch item {
        case let .live(c, _), let .catchup(c, _):
            return PlayerPreferences.companion.contentKey(sourceId: c.sourceId, type: "LIVE", id: c.id)
        case let .movie(m):
            return PlayerPreferences.companion.contentKey(sourceId: m.sourceId, type: "MOVIE", id: m.id)
        case let .episode(_, e, _):
            return PlayerPreferences.companion.contentKey(sourceId: e.sourceId, type: "EPISODE", id: e.id)
        }
    }

    private func loadDelays() async {
        let key = contentKey
        guard delaysKey != key else { return }
        delaysKey = key
        audioDelayMs = (try? await core.playerPreferences.audioDelayMs(contentKey: key))?.int64Value ?? 0
        subtitleDelayMs = (try? await core.playerPreferences.subtitleDelayMs(contentKey: key))?.int64Value ?? 0
    }

    func setAudioDelay(_ ms: Int64) { applyDelays(audio: min(3000, max(-3000, ms)), subtitle: subtitleDelayMs) }
    func setSubtitleDelay(_ ms: Int64) { applyDelays(audio: audioDelayMs, subtitle: min(10_000, max(-10_000, ms))) }

    private func applyDelays(audio: Int64, subtitle: Int64) {
        interaction()
        guard let engine else { return }
        if engine.supportsDelays {
            audioDelayMs = audio
            subtitleDelayMs = subtitle
            engine.setDelays(audioMs: audio, subtitleMs: subtitle)
            persistDelays()
        } else if audio != 0 || subtitle != 0 {
            if VLCSupport.isAvailable {
                // AVPlayer has no offset API: reopen the same stream with the VLC engine.
                audioDelayMs = audio
                subtitleDelayMs = subtitle
                persistDelays()
                delayNotice = "Per regolare la sincronizzazione uso il lettore avanzato"
                forceVLC = true
                if !isLive && currentTime > 1 { resumeAt = currentTime }
                Task { await start() }
            } else {
                // Never fake it.
                delayNotice = "La sincronizzazione non è supportata per questo stream"
            }
        } else {
            audioDelayMs = 0
            subtitleDelayMs = 0
            persistDelays()
        }
    }

    private func persistDelays() {
        let key = contentKey
        let a = audioDelayMs, s = subtitleDelayMs
        Task {
            _ = try? await core.playerPreferences.setAudioDelayMs(contentKey: key, ms: a)
            _ = try? await core.playerPreferences.setSubtitleDelayMs(contentKey: key, ms: s)
        }
    }

    // MARK: App lifecycle

    private func observeLifecycle() {
        let nc = NotificationCenter.default
        lifecycleObservers = [
            nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.didEnterBackground() }
            },
            nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                Task { @MainActor in self?.willEnterForeground() }
            },
        ]
    }

    private func didEnterBackground() {
        guard !isClosed, let engine else { return }
        #if os(iOS)
        if let av = engine as? AVPlayerEngine, av.isPictureInPictureActive || av.isExternalPlaybackActive { return }
        #endif
        // No PiP/AirPlay: nobody is watching, free the connection.
        saveProgress()
        if !isLive && currentTime > 1 { resumeAt = currentTime }
        cancelPendingRetry()
        zapTask?.cancel()
        startToken += 1
        teardown()
        suspendedInBackground = true
    }

    private func willEnterForeground() {
        guard suspendedInBackground, !isClosed else { return }
        suspendedInBackground = false
        // Like Android: an error screen stays as it is (no connection opened behind the user's back);
        // "Riprova" restarts on request.
        if failure != nil { return }
        Task { await start() }
    }

    // MARK: Audio session (calls, Siri, alarms, headphones)

    private func observeAudioSession() {
        let nc = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        lifecycleObservers += [
            nc.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
                let info = note.userInfo ?? [:]
                guard let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                      let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
                let options = AVAudioSession.InterruptionOptions(rawValue: info[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0)
                MainActor.assumeIsolated { self?.audioInterruption(began: type == .began, shouldResume: options.contains(.shouldResume)) }
            },
            nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { [weak self] note in
                guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                      AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
                MainActor.assumeIsolated { self?.audioRouteLost() }
            },
        ]
    }

    /// Incoming call / Siri / alarm: pause (the system already silenced us); resume afterwards when allowed.
    private func audioInterruption(began: Bool, shouldResume: Bool) {
        guard !isClosed, let engine else { return }
        if began {
            guard !systemPaused else { return }
            resumeAfterInterruption = !userPaused
            systemPaused = true
            engine.pause()
            watchdog.reset() // a paused stream is not a stalled one
        } else {
            guard systemPaused else { return }
            systemPaused = false
            if shouldResume && resumeAfterInterruption {
                try? AVAudioSession.sharedInstance().setActive(true)
                engine.play()
            } else {
                userPaused = true // stays paused until the user presses play
            }
            resumeAfterInterruption = false
            watchdog.reset()
        }
        syncEngineState()
    }

    /// Headphones / Bluetooth / AirPlay speaker went away: pause instead of playing out loud (Apple HIG).
    private func audioRouteLost() {
        guard !isClosed, let engine, !userPaused else { return }
        engine.pause()
        userPaused = true
        watchdog.reset()
        syncEngineState()
    }

    private final class OnceFlag: @unchecked Sendable {
        private let lock = NSLock()
        private var fired = false
        func fire() -> Bool { lock.withLock { if fired { return false }; fired = true; return true } }
    }

    private static func onMain(_ work: @escaping @MainActor () -> Void) {
        if Thread.isMainThread {
            MainActor.assumeIsolated { work() }
        } else {
            DispatchQueue.main.async { work() }
        }
    }
}

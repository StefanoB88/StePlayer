import Foundation
import UIKit
import Observation
import Shared

/// Something the user wants to watch. Drives the full-screen player.
enum PlayItem: Identifiable {
    case live(Channel, zapList: [Channel])
    case catchup(Channel, EpgProgram)
    case movie(Movie)
    case episode(Series, Episode, all: [Episode])

    var id: String {
        switch self {
        case let .live(c, _): return "live:\(c.id)"
        case let .catchup(c, p): return "catchup:\(c.id):\(p.startAt)"
        case let .movie(m): return "movie:\(m.id)"
        case let .episode(_, e, _): return "episode:\(e.id)"
        }
    }
}

/// Android-style confirmation dialog shown above everything (RootView).
struct ConfirmDialog: Identifiable {
    let id = UUID()
    let title: String
    let message: String
    let confirmTitle: String
    let action: () -> Void
}

/// Blocking "Aggiornamento della lista" popup shown over the whole app (RootView) for user-initiated syncs.
enum SyncPopup: Equatable {
    case running
    case failed(String)
}

/// App-wide state: owns the Kotlin core and the list lifecycle.
@MainActor
@Observable
final class AppModel {
    enum Phase: Equatable { case launching, onboarding, syncing, ready }

    let core: StePlayerCore

    /// Lists, favourites and history stay on this device only (privacy policy): the SQLite folder
    /// (SQLiter's default "Library/Application Support/databases") is kept out of iCloud/computer backups.
    /// Secrets are already Keychain "ThisDeviceOnly", downloads are excluded where they are created.
    private static func excludeDatabaseFromBackup() {
        let fm = FileManager.default
        guard let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
        var dir = support.appendingPathComponent("databases", isDirectory: true)
        try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? dir.setResourceValues(values)
    }
    let downloadsDirectory: String

    var phase: Phase = .launching
    var sources: [Source] = []
    var active: Source?
    var syncProgress: Double = 0
    var syncStepText = ""
    var syncError: String?
    var isSyncing = false
    var playing: PlayItem?
    var expiryNotices: [ExpiryNotice] = []
    /// Short message shown over the UI (e.g. "Timer di spegnimento terminato").
    var toast: String?
    var remindersEnabled = true
    /// Selected bottom-bar / rail tab and the pushed full-screen routes (Android nav graph).
    var tab: AppTab = .home
    var path: [Route] = []
    var confirm: ConfirmDialog?
    /// Non-nil while the user-initiated sync popup is on screen (never for background/startup syncs).
    var syncPopup: SyncPopup?
    @ObservationIgnored private var syncPopupSourceId: Int64 = -1

    #if os(iOS)
    let downloadEngine: BackgroundDownloadEngine
    #endif

    @ObservationIgnored private var subscriptions: [FlowSubscription] = []

    init() {
        #if os(tvOS)
        let isTV = true
        let ua = "StePlayer/1.0 (tvOS)"
        #else
        let isTV = false
        let ua = "StePlayer/1.0 (iOS)"
        #endif
        Self.excludeDatabaseFromBackup()
        let platform = ApplePlatform(credentialStore: KeychainCredentialStore(), defaultUserAgent: ua, useCachesForDownloads: isTV)
        downloadsDirectory = platform.downloadsDirectory
        core = StePlayerCore(platform: platform)
        #if os(iOS)
        downloadEngine = BackgroundDownloadEngine(downloads: core.downloads, connections: core.playback.connections, directory: platform.downloadsDirectory)
        core.downloads.engine = downloadEngine
        #endif
        start()
    }

    var activeId: Int64 { active?.id ?? -1 }

    #if DEBUG
    /// Development only (never in release builds): `STEPLAYER_DEMO_M3U=<url>` loads a test list
    /// on a fresh install; `STEPLAYER_DEMO_AUTOPLAY=1` then opens the first channel. Used to take
    /// simulator screenshots without typing in the onboarding form.
    private func startDemoIfRequested() {
        let env = ProcessInfo.processInfo.environment
        guard let url = env["STEPLAYER_DEMO_M3U"] else { return }
        // `STEPLAYER_DEMO_TAB=home|live|movies|series|downloads|search|settings` opens that screen.
        switch env["STEPLAYER_DEMO_TAB"] {
        case "search": path = [.search]
        case "settings": path = [.settings]
        case let name?: tab = AppTab(rawValue: name) ?? .home
        default: break
        }
        #if os(iOS)
        DemoAutoScroll.runIfRequested()
        #endif
        Task { @MainActor [weak self] in
            guard let self else { return }
            // `STEPLAYER_DEMO_SUBS=always`: subtitles on "Sempre" (checks automatic subtitle selection on a fresh install).
            if env["STEPLAYER_DEMO_SUBS"] == "always", let s = try? await core.playerPreferences.current() {
                let updated = s.doCopy(stillWatchingAfterHours: s.stillWatchingAfterHours, bufferMode: s.bufferMode,
                                       preferredAudioLanguages: s.preferredAudioLanguages, preferredSubtitleLanguage: "it",
                                       subtitleMode: .always, subtitleStyle: s.subtitleStyle, showStatsOverlay: s.showStatsOverlay)
                try? await core.playerPreferences.save(settings: updated)
            }
            let existing = (try? await core.sources.current()) ?? nil
            if existing == nil {
                UserDefaults.standard.set(true, forKey: "legalAccepted")
                try? await addSource(SourceInputM3u(name: "Lista di prova", listUrl: url, epgUrl: nil, userAgent: nil))
            }
            if let target = env["STEPLAYER_DEMO_OPEN"] { // movie | series | guide: open the first item's screen
                while phase != .ready { try? await Task.sleep(nanoseconds: 300_000_000) }
                try? await Task.sleep(nanoseconds: 800_000_000)
                guard let id = active?.id else { return }
                switch target {
                case "movie":
                    demoSubscription = observeList(core.catalog.movies(sourceId: id, categoryId: "__all__", limit: 1, offset: 0), as: Movie.self) { [weak self] l in
                        guard let self, let m = l.first, self.demoSubscription != nil else { return }
                        self.demoSubscription = nil
                        self.path = [.movie(sourceId: id, id: m.id)]
                    }
                case "series":
                    demoSubscription = observeList(core.catalog.series(sourceId: id, categoryId: "__all__", limit: 1, offset: 0), as: Series.self) { [weak self] l in
                        guard let self, let s = l.first, self.demoSubscription != nil else { return }
                        self.demoSubscription = nil
                        self.path = [.series(sourceId: id, id: s.id)]
                    }
                case "conntest":
                    path = [.connectionTest(sourceId: id)]
                case "sendtv":
                    path = [.sendToTv]
                default:
                    path = [.guide(sourceId: id, categoryId: "__all__")]
                }
            }
            if env["STEPLAYER_DEMO_RESYNC"] == "1" { // profiling: a background re-sync while a list is on screen
                while phase != .ready { try? await Task.sleep(nanoseconds: 300_000_000) }
                try? await Task.sleep(nanoseconds: 2_000_000_000)
                if let id = active?.id { await syncNow(id, blocking: false) }
            }
            // `STEPLAYER_DEMO_AUTOPLAY_MOVIE=<n>` plays the n-th film (0-based): engine routing checks.
            if let n = env["STEPLAYER_DEMO_AUTOPLAY_MOVIE"].flatMap(Int64.init) {
                while phase != .ready { try? await Task.sleep(nanoseconds: 300_000_000) }
                try? await Task.sleep(nanoseconds: 800_000_000)
                guard let id = active?.id else { return }
                demoSubscription = observeList(core.catalog.movies(sourceId: id, categoryId: "__all__", limit: 1, offset: n), as: Movie.self) { [weak self] l in
                    guard let self, let m = l.first, self.demoSubscription != nil else { return }
                    self.demoSubscription = nil
                    self.play(.movie(m))
                }
                return
            }
            guard env["STEPLAYER_DEMO_AUTOPLAY"] == "1" else { return }
            while phase != .ready { try? await Task.sleep(nanoseconds: 300_000_000) }
            try? await Task.sleep(nanoseconds: 800_000_000)
            guard let id = active?.id else { return }
            demoSubscription = observeList(core.catalog.channels(sourceId: id, categoryId: "__all__"), as: Channel.self) { [weak self] list in
                guard let self, let ch = list.first, self.demoSubscription != nil else { return }
                self.demoSubscription = nil
                self.play(.live(ch, zapList: list))
            }
        }
    }

    @ObservationIgnored private var demoSubscription: FlowSubscription?
    #endif

    private func start() {
        #if DEBUG
        startDemoIfRequested()
        #endif
        subscriptions.append(observeList(core.sources.sources, as: Source.self) { [weak self] list in
            guard let self else { return }
            self.sources = list
            if list.isEmpty {
                self.phase = .onboarding
            } else if self.phase == .launching {
                self.phase = .ready
            }
            // Expiry dates arrive with sync/add/edit/delete: keep reminders in step.
            self.rescheduleReminders()
        })
        subscriptions.append(observe(core.sources.activeSource) { [weak self] (source: Source) in
            guard let self else { return }
            let previous = self.active?.id
            self.active = source.id >= 0 ? source : nil
            if let s = self.active, previous != s.id {
                if s.lastSyncAt == nil && !self.isSyncing {
                    Task { await self.syncNow(s.id, blocking: true) }
                } else {
                    Task { try? await self.core.sync.syncIfStale(sourceId: s.id, maxAgeMs: 12 * 3600 * 1000) }
                }
            }
        })
        subscriptions.append(observe(core.sync.state) { [weak self] state in
            self?.apply(syncState: state)
        })
        subscriptions.append(observeList(core.reminders.notices, as: ExpiryNotice.self) { [weak self] in
            self?.expiryNotices = $0
        })
        subscriptions.append(observe(core.reminders.enabled) { [weak self] (value: KotlinBoolean) in
            self?.remindersEnabled = value.boolValue
        })
        #if os(iOS)
        Task { await downloadEngine.restorePending() }
        #endif
    }

    private func apply(syncState state: AnyObject) {
        if let running = state as? SyncStateRunning {
            isSyncing = true
            syncProgress = Double(running.progress)
            syncStepText = Self.text(for: running.step)
        } else if let failed = state as? SyncStateFailed {
            isSyncing = false
            syncError = failed.message
        } else {
            isSyncing = false
            if state is SyncStateDone {
                syncProgress = 1
                rescheduleReminders()
            }
        }
    }

    private static func text(for step: SyncStep) -> String {
        switch step {
        case .authenticating: return "Accesso al server…"
        case .live: return "Scarico i canali live…"
        case .movies: return "Scarico i film…"
        case .series: return "Scarico le serie TV…"
        case .epg: return "Scarico la guida TV…"
        default: return "Salvataggio…"
        }
    }

    // MARK: - Lists

    /// Validates, stores and syncs a new list. Throws with a readable message.
    func addSource(_ input: SourceInput) async throws {
        _ = try await core.sources.add(input: input)
        // The new list becomes active; the activeSource observer starts its first sync.
        syncError = nil
        syncProgress = 0
        phase = .syncing
    }

    /// A list arrived from the phone (QR pairing): show the first-sync screen.
    func pairingCompleted(_ id: Int64) {
        syncError = nil
        syncProgress = 0
        phase = .syncing
        Task {
            await activate(id)
            if active?.id == id, active?.lastSyncAt == nil, !isSyncing { await syncNow(id, blocking: true) }
        }
    }

    func updateSource(_ id: Int64, _ input: SourceInput) async throws {
        try await core.sources.update(id: id, input: input)
        await syncNow(id, blocking: false)
    }

    func deleteSource(_ id: Int64) async {
        try? await core.sources.delete(id: id)
    }

    func activate(_ id: Int64) async {
        _ = try? await core.sources.setActive(sourceId: id)
    }

    /// Full sync. When `blocking`, the full-screen progress is shown (first sync).
    func syncNow(_ id: Int64, blocking: Bool) async {
        guard !isSyncing else { return }
        isSyncing = true
        syncError = nil
        if blocking { phase = .syncing }
        do {
            try await core.sync.sync(sourceId: id)
            if blocking { phase = .ready }
        } catch {
            syncError = readableError(error)
        }
        isSyncing = false
    }

    /// "Aggiorna" / "Sincronizza ora": full sync behind the blocking progress popup.
    /// Success closes it with the "Lista aggiornata" toast; failure keeps it open with Riprova / Chiudi.
    func userSync(_ id: Int64) {
        guard id >= 0, syncPopup == nil else { return }
        syncPopupSourceId = id
        runPopupSync()
    }

    /// "Riprova" in the failed popup: same list, same popup.
    func retryPopupSync() {
        guard case .failed = syncPopup else { return }
        runPopupSync()
    }

    /// "Chiudi" in the failed popup.
    func dismissSyncPopup() {
        guard case .failed = syncPopup else { return }
        syncPopup = nil
    }

    private func runPopupSync() {
        let id = syncPopupSourceId
        syncProgress = 0
        syncStepText = ""
        syncPopup = .running
        Task {
            // An automatic sync may already be running: the popup follows it, then runs the requested one.
            while isSyncing { try? await Task.sleep(for: .milliseconds(200)) }
            await syncNow(id, blocking: false)
            if let error = syncError {
                syncPopup = .failed(error)
            } else {
                syncPopup = nil
                showToast("Lista aggiornata")
            }
        }
    }

    func refreshEpg() async throws {
        guard let source = active else { return }
        try await core.epg.refresh(source: source)
    }

    /// Start position for the next player (nil = resume from saved progress). Consumed by PlayerScreen.
    @ObservationIgnored var pendingStartAt: Double?

    func play(_ item: PlayItem, fromStart: Bool = false) {
        pendingStartAt = fromStart ? 0 : nil
        #if os(iOS)
        // iPhone: rotate to landscape first, then present: the player comes up already landscape.
        guard playing == nil, UIDevice.current.userInterfaceIdiom == .phone, !AppDelegate.isLandscape else {
            playing = item
            return
        }
        let token = UUID()
        playToken = token
        Task { @MainActor [weak self] in
            await AppDelegate.rotateForPlayer()
            guard let self, self.playToken == token, self.playing == nil else { return }
            self.playing = item
        }
        #else
        playing = item
        #endif
    }

    /// The latest `play` request (a second tap during the rotation replaces the first).
    @ObservationIgnored private var playToken: UUID?

    // MARK: - Navigation (Android AppActions)

    func open(_ route: Route) { path.append(route) }

    func back() { if !path.isEmpty { path.removeLast() } }

    @ObservationIgnored private var channelListSub: FlowSubscription?

    /// Plays a channel with its category as the zapping list (Android `playChannel(channel, categoryId)`).
    func playChannel(_ channel: Channel, categoryId: String?, list: [Channel]? = nil) {
        if let list, !list.isEmpty { play(.live(channel, zapList: list)); return }
        let category = categoryId ?? channel.categoryId ?? "__all__"
        var delivered = false
        channelListSub = observeList(core.catalog.channels(sourceId: channel.sourceId, categoryId: category), as: Channel.self) { [weak self] list in
            guard let self, !delivered else { return }
            delivered = true
            self.channelListSub = nil
            self.play(.live(channel, zapList: list.contains(where: { $0.id == channel.id }) ? list : [channel]))
        }
    }

    /// Opens an item from "Continua a guardare" / "Canali recenti" (Android `playProgress`).
    func playProgress(_ p: WatchProgress) async {
        if p.type == ContentType.live {
            if let ch = try? await core.catalog.channel(sourceId: p.sourceId, id: p.itemId) { playChannel(ch, categoryId: nil) }
        } else if p.type == ContentType.movie {
            if let movie = try? await core.catalog.movie(sourceId: p.sourceId, id: p.itemId) { play(.movie(movie)) }
        } else if p.type == ContentType.episode, let seriesId = p.parentId {
            guard let series = try? await core.catalog.seriesById(sourceId: p.sourceId, id: seriesId) else { return }
            let detail = try? await core.catalog.seriesDetail(series: series)
            let all = detail?.episodes ?? []
            if let ep = all.first(where: { $0.id == p.itemId }) {
                play(.episode(series, ep, all: all))
            } else if let ep = try? await core.catalog.episode(sourceId: p.sourceId, id: p.itemId) {
                play(.episode(series, ep, all: [ep]))
            }
        } else if p.type == ContentType.series {
            open(.series(sourceId: p.sourceId, id: p.itemId))
        }
    }

    // MARK: - Buffer advisor (one per app session, recreated if the user changes mode)

    @ObservationIgnored private var advisor: BufferAdvisor?
    @ObservationIgnored private var advisorMode: BufferMode?

    func bufferAdvisor(for mode: BufferMode) -> BufferAdvisor {
        if let advisor, advisorMode == mode { return advisor }
        let fresh = BufferAdvisor(mode: mode)
        advisor = fresh
        advisorMode = mode
        return fresh
    }

    func showToast(_ message: String) {
        toast = message
        Task {
            try? await Task.sleep(for: .seconds(3))
            if toast == message { toast = nil }
        }
    }

    // MARK: - Expiry reminders

    @ObservationIgnored private var rescheduleTask: Task<Void, Never>?

    func rescheduleReminders() {
        rescheduleTask?.cancel()
        rescheduleTask = Task {
            try? await Task.sleep(for: .milliseconds(400)) // coalesce bursts of updates
            guard !Task.isCancelled else { return }
            await ExpiryReminderScheduler.reschedule(core: core, sources: sources)
        }
    }

    func setRemindersEnabled(_ value: Bool) async {
        _ = try? await core.reminders.setEnabled(value: value)
        rescheduleReminders()
    }
}

/// Development-only switches read from the environment (never active in release builds).
enum DemoHooks {
    #if DEBUG
    /// `STEPLAYER_DEMO_CATEGORY=<id>` (e.g. `__all__`): initial Live / Film / Serie category, to profile big lists.
    static let category = ProcessInfo.processInfo.environment["STEPLAYER_DEMO_CATEGORY"]
    #else
    static let category: String? = nil
    #endif
}

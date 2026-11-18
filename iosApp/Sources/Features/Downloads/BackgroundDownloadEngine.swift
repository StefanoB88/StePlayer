#if os(iOS)
import Foundation
import Shared
import UIKit

/// Kotlin `DownloadEngine` on top of a background URLSession:
/// downloads keep going when the app is suspended or killed by the system.
/// Every transfer holds a connection lease, so downloads never exceed the account's
/// `max_connections` and always leave room for playback.
///
/// Fresh bursts: many providers send each new connection at full speed for a while, then throttle
/// it. While the app is in the foreground, `ConnectionRefresher` learns how long the burst lasts and
/// the task is renewed just before the slowdown: cancelled with resume data, then restarted from
/// that data (a Range request from the same byte). The old task is cancelled before the new one
/// starts and the lease is kept, so it is still one connection. Only for servers that support
/// resuming (Accept-Ranges + ETag/Last-Modified); in the background iOS runs the transfer alone.
final class BackgroundDownloadEngine: NSObject, DownloadEngine, URLSessionDownloadDelegate, @unchecked Sendable {
    static let sessionIdentifier = "com.steplayer.downloads"
    /// Set by the AppDelegate when iOS relaunches us for finished background transfers.
    static var backgroundCompletionHandler: (() -> Void)?

    private let downloads: DownloadRepository
    private let connections: ConnectionGuard
    /// Main-thread only.
    private var leases: [String: ConnectionLease] = [:]
    /// Requests waiting for a free connection (stay QUEUED in the database). Main-thread only.
    private var waiting: [String: DownloadRequest] = [:]
    private var activeSubscription: FlowSubscription?
    private let directory: URL
    private let resumeDirectory: URL
    private let lock = NSLock()
    private var lastReport: [String: Date] = [:]
    private var pausing: Set<String> = []
    /// Deleted while a transfer may still be finishing: its file / resume data must not come back. Guarded by `lock`.
    private var deleted: Set<String> = []
    /// Bumped by every start / pause / delete of an id, so a start that was still waiting for a connection
    /// lease (or for `getAllTasks`) can tell it was overtaken. Guarded by `lock`.
    private var epochs: [String: Int] = [:]
    /// Smoothed throughput per download: (lastBytes, lastTime, bytesPerSecond). Guarded by `lock`.
    private var meters: [String: (Int64, Date, Double)] = [:]
    /// Automatic resume attempts after transient network errors (memory only). Main-thread only.
    private var retryAttempts: [String: Int] = [:]
    private static let maxAutoRetries = 8

    // Fresh bursts (all guarded by `lock`).
    private var refreshers: [String: ConnectionRefresher] = [:]
    /// Task currently measured by each download's refresher, and the foreground period it started in.
    private var measured: [String: (task: Int, generation: Int)] = [:]
    /// Tasks cancelled or refused during a renewal: their completion must not release the lease or fail.
    private var replaced: Set<Int> = []
    /// Renewal task that hasn't delivered data yet (its resume file is kept until it does).
    private var renewalTask: [String: Int] = [:]
    private var renewRejections: [String: Int] = [:]
    private var foreground = false
    private var foregroundGeneration = 0
    private var lifecycleObservers: [NSObjectProtocol] = []
    private static let maxRenewRejections = 5

    private lazy var session: URLSession = {
        let config = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = true
        config.httpMaximumConnectionsPerHost = 2
        config.timeoutIntervalForResource = 60 * 60 * 24
        return URLSession(configuration: config, delegate: self, delegateQueue: nil)
    }()

    init(downloads: DownloadRepository, connections: ConnectionGuard, directory: String) {
        self.downloads = downloads
        self.connections = connections
        self.directory = URL(fileURLWithPath: directory, isDirectory: true)
        self.resumeDirectory = self.directory.appendingPathComponent(".resume", isDirectory: true)
        super.init()
        let fm = FileManager.default
        try? fm.createDirectory(at: resumeDirectory, withIntermediateDirectories: true)
        var url = self.directory
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        _ = session // reconnect to the background session early
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            // A connection was freed (playback closed, another download finished): try the queue again.
            self.activeSubscription = observeList(connections.active, as: LeaseInfo.self) { [weak self] _ in
                self?.drainWaiting()
            }
            self.observeForeground()
        }
    }

    private func observeForeground() {
        let setForeground: (Bool) -> Void = { [weak self] active in
            guard let self else { return }
            self.lock.withLock {
                if active && !self.foreground { self.foregroundGeneration += 1 }
                self.foreground = active
            }
        }
        setForeground(UIApplication.shared.applicationState == .active)
        let center = NotificationCenter.default
        lifecycleObservers = [
            center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in setForeground(true) },
            center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in setForeground(false) },
        ]
    }

    // MARK: Leases (main thread)

    private static func sourceId(of id: String) -> Int64 {
        Int64(id.split(separator: ":").first ?? "") ?? -1
    }

    private func onMain(_ work: @escaping () -> Void) {
        if Thread.isMainThread { work() } else { DispatchQueue.main.async(execute: work) }
    }

    private func releaseLease(_ id: String) {
        onMain { [self] in
            leases.removeValue(forKey: id)?.release()
            waiting.removeValue(forKey: id)
        }
    }

    private var draining = false

    private func bumpEpoch(_ id: String) -> Int {
        lock.withLock {
            let next = (epochs[id] ?? 0) + 1
            epochs[id] = next
            return next
        }
    }

    private func epoch(_ id: String) -> Int { lock.withLock { epochs[id] ?? 0 } }

    private func drainWaiting() {
        guard !draining, let request = waiting.values.first else { return }
        draining = true
        let started = epoch(request.id)
        Task { @MainActor [self] in
            defer { draining = false }
            if await acquire(for: request) {
                waiting.removeValue(forKey: request.id)
                guard epoch(request.id) == started else { releaseLease(request.id); drainWaiting(); return }
                transfer(request, epoch: started)
                drainWaiting()
            }
        }
    }

    @MainActor
    private func acquire(for request: DownloadRequest) async -> Bool {
        if leases[request.id] != nil { return true }
        guard let lease = try? await connections.acquireDownload(sourceId: Self.sourceId(of: request.id), label: request.title) else {
            return false
        }
        leases[request.id] = lease
        return true
    }

    // MARK: DownloadEngine

    func start(request: DownloadRequest) {
        lock.withLock {
            pausing.remove(request.id)
            deleted.remove(request.id)
        }
        let started = bumpEpoch(request.id)
        onMain { [self] in
            Task { @MainActor in
                if await acquire(for: request) {
                    waiting.removeValue(forKey: request.id)
                    // Paused or deleted while waiting for the lease: give the connection back, start nothing.
                    guard epoch(request.id) == started else { releaseLease(request.id); return }
                    transfer(request, epoch: started)
                } else {
                    guard epoch(request.id) == started else { return }
                    // No free connection: stays QUEUED until `connections.active` changes.
                    waiting[request.id] = request
                }
            }
        }
    }

    private func transfer(_ request: DownloadRequest, epoch started: Int) {
        session.getAllTasks { [self] tasks in
            guard epoch(request.id) == started else { releaseLease(request.id); return }
            if tasks.contains(where: { Self.parse($0.taskDescription)?.id == request.id && $0.state == .running }) { return }
            let task: URLSessionDownloadTask
            if let data = try? Data(contentsOf: resumeFile(request.id)) {
                task = session.downloadTask(withResumeData: data)
                try? FileManager.default.removeItem(at: resumeFile(request.id))
            } else {
                guard let url = URL(string: request.url) else {
                    downloads.onFailed(id: request.id, message: "URL non valido")
                    releaseLease(request.id)
                    return
                }
                var urlRequest = URLRequest(url: url)
                request.headers.forEach { urlRequest.setValue($0.value, forHTTPHeaderField: $0.key) }
                task = session.downloadTask(with: urlRequest)
            }
            task.taskDescription = "\(request.id)|\(request.fileName)"
            task.resume()
        }
    }

    func pause(id: String) {
        lock.lock(); pausing.insert(id); lock.unlock()
        _ = bumpEpoch(id)
        releaseLease(id)
        session.getAllTasks { [self] tasks in
            for case let task as URLSessionDownloadTask in tasks where Self.parse(task.taskDescription)?.id == id {
                task.cancel { data in
                    if let data { try? data.write(to: self.resumeFile(id)) }
                }
            }
        }
    }

    func cancel(id: String) {
        lock.lock(); pausing.insert(id); deleted.insert(id); lock.unlock()
        _ = bumpEpoch(id)
        releaseLease(id)
        session.getAllTasks { [self] tasks in
            tasks.filter { Self.parse($0.taskDescription)?.id == id }.forEach { $0.cancel() }
            let fm = FileManager.default
            try? fm.removeItem(at: resumeFile(id))
            if let files = try? fm.contentsOfDirectory(atPath: directory.path) {
                let prefix = id.replacingOccurrences(of: ":", with: "_") + "."
                files.filter { $0.hasPrefix(prefix) }.forEach { try? fm.removeItem(at: directory.appendingPathComponent($0)) }
            }
        }
    }

    /// Restarts queued/running downloads that have no live task (e.g. after the app was force-quit).
    @MainActor
    func restorePending() async {
        let pending = (try? await downloads.pending()) ?? []
        guard !pending.isEmpty else { return }
        let tasks = await session.allTasks
        // Transfers that survived in the background session need a lease too; if the budget is
        // exhausted, park them (resume data is kept) and queue them again.
        for task in tasks {
            guard let info = Self.parse(task.taskDescription), leases[info.id] == nil else { continue }
            if let lease = try? await connections.acquireDownload(sourceId: Self.sourceId(of: info.id), label: info.fileName) {
                leases[info.id] = lease
            } else {
                pause(id: info.id)
                try? await downloads.resume(id: info.id)
            }
        }
        let alive = Set(tasks.compactMap { Self.parse($0.taskDescription)?.id })
        for d in pending where !alive.contains(d.id) {
            try? await downloads.resume(id: d.id)
        }
    }

    // MARK: URLSessionDownloadDelegate

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let info = Self.parse(downloadTask.taskDescription) else { return }
        let now = Date()
        lock.lock()
        let last = lastReport[info.id] ?? .distantPast
        let shouldReport = now.timeIntervalSince(last) >= 1
        var speed: DownloadSpeedStore.Speed?
        if shouldReport {
            lastReport[info.id] = now
            let previous = meters[info.id]
            var bps = previous?.2 ?? 0
            if let (bytes, time) = previous.map({ ($0.0, $0.1) }), now > time {
                let instant = Double(totalBytesWritten - bytes) / now.timeIntervalSince(time)
                bps = bps == 0 ? instant : bps * 0.7 + instant * 0.3
            }
            meters[info.id] = (totalBytesWritten, now, bps)
            if bps > 0 {
                let remaining = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite - totalBytesWritten : -1
                speed = .init(bytesPerSecond: Int64(bps), etaSeconds: remaining > 0 ? Int64(Double(remaining) / bps) : nil)
            }
        }
        lock.unlock()
        if let speed {
            let id = info.id
            Task { @MainActor in DownloadSpeedStore.shared.set(id, speed) }
        }
        if shouldReport {
            downloads.onProgress(id: info.id, bytes: totalBytesWritten, total: max(totalBytesExpectedToWrite, 0))
        }
        measureBurst(downloadTask, info: info, bytes: bytesWritten)
    }

    // MARK: Fresh bursts

    /// Feeds the refresher; renews the task when it asks to.
    private func measureBurst(_ task: URLSessionDownloadTask, info: (id: String, fileName: String), bytes: Int64) {
        guard let http = task.response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else { return }
        let id = info.id
        let taskId = task.taskIdentifier
        var dropResumeFile = false
        var renewAfter: Int64?
        lock.lock()
        if !replaced.contains(taskId) {
            if renewalTask[id] == taskId {
                // The renewed connection is delivering: its resume data is no longer needed.
                renewalTask[id] = nil
                renewRejections[id] = nil
                dropResumeFile = true
            }
            if foreground && Self.isResumable(http) {
                let refresher = refreshers[id] ?? ConnectionRefresher()
                refreshers[id] = refresher
                if measured[id]?.task != taskId || measured[id]?.generation != foregroundGeneration {
                    // New connection, or back from the background (the gap would look like a slowdown).
                    measured[id] = (taskId, foregroundGeneration)
                    refresher.connected()
                }
                if refresher.onBytes(bytes) {
                    replaced.insert(taskId)
                    renewAfter = refresher.pauseMs
                }
            }
        }
        lock.unlock()
        if dropResumeFile { try? FileManager.default.removeItem(at: resumeFile(id)) }
        if let pause = renewAfter { renew(task, id: id, fileName: info.fileName, pauseMs: pause) }
    }

    /// Cancels the throttled task keeping its resume data, then restarts from it after a short pause.
    private func renew(_ task: URLSessionDownloadTask, id: String, fileName: String, pauseMs: Int64) {
        let started = epoch(id)
        task.cancel(byProducingResumeData: { [self] data in
            guard let data else { handOver(id); return }
            try? data.write(to: resumeFile(id))
            DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(pauseMs))) { [self] in
                startRenewal(id: id, fileName: fileName, epoch: started)
            }
        })
    }

    private func startRenewal(id: String, fileName: String, epoch started: Int) {
        if lock.withLock({ deleted.contains(id) }) {
            try? FileManager.default.removeItem(at: resumeFile(id))
            return
        }
        // Paused meanwhile: pause() already gave the lease back; the resume file stays for later.
        guard epoch(id) == started else { return }
        guard let data = try? Data(contentsOf: resumeFile(id)) else { handOver(id); return }
        let task = session.downloadTask(withResumeData: data)
        task.taskDescription = "\(id)|\(fileName)"
        lock.withLock { renewalTask[id] = task.taskIdentifier }
        task.resume()
    }

    /// The renewed connection was refused (the provider may still count the one just closed):
    /// retry after a longer pause. Returns false when the normal error handling should take over.
    private func retryRefusedRenewal(_ task: URLSessionDownloadTask, info: (id: String, fileName: String)) -> Bool {
        let id = info.id
        var pause: Int64?
        lock.lock()
        if renewalTask[id] == task.taskIdentifier {
            renewalTask[id] = nil
            let attempt = (renewRejections[id] ?? 0) + 1
            if attempt <= Self.maxRenewRejections {
                renewRejections[id] = attempt
                let refresher = refreshers[id]
                refresher?.rejected()
                pause = refresher?.pauseMs ?? ConnectionRefresher.maxPauseMs
                replaced.insert(task.taskIdentifier)
            } else {
                renewRejections[id] = nil
            }
        }
        lock.unlock()
        guard let pause else { return false }
        let started = epoch(id)
        DispatchQueue.global().asyncAfter(deadline: .now() + .milliseconds(Int(pause))) { [self] in
            startRenewal(id: id, fileName: info.fileName, epoch: started)
        }
        return true
    }

    /// No resume data after all: hand the download to the normal automatic-retry path.
    private func handOver(_ id: String) {
        lock.withLock { refreshers[id]?.disable() }
        downloads.onQueued(id: id)
        releaseLease(id)
        Task { @MainActor in self.scheduleAutoResume(id) }
    }

    /// URLSession can only resume with ranges and a validator (ETag or Last-Modified).
    private static func isResumable(_ http: HTTPURLResponse) -> Bool {
        let ranges = http.statusCode == 206 || http.value(forHTTPHeaderField: "Accept-Ranges")?.lowercased() == "bytes"
        let validator = http.value(forHTTPHeaderField: "ETag") != nil || http.value(forHTTPHeaderField: "Last-Modified") != nil
        return ranges && validator
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let info = Self.parse(downloadTask.taskDescription) else { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode),
           retryRefusedRenewal(downloadTask, info: info) { return }
        defer { releaseLease(info.id) }
        // Deleted during the last bytes: drop the file (iOS removes `location` when we return).
        if lock.withLock({ deleted.contains(info.id) }) { return }
        if let http = downloadTask.response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            if [429, 458, 509].contains(http.statusCode) || (500..<600).contains(http.statusCode) {
                // Busy/overloaded provider: retry later instead of failing.
                downloads.onQueued(id: info.id)
                let id = info.id
                Task { @MainActor in self.scheduleAutoResume(id) }
            } else {
                downloads.onFailed(id: info.id, message: "Il server ha risposto con errore \(http.statusCode)")
            }
            return
        }
        let destination = directory.appendingPathComponent(info.fileName)
        let fm = FileManager.default
        do {
            try? fm.removeItem(at: destination)
            try fm.moveItem(at: location, to: destination)
            downloads.onCompleted(id: info.id, fileName: info.fileName)
        } catch {
            downloads.onFailed(id: info.id, message: "Impossibile salvare il file: \(error.localizedDescription)")
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let info = Self.parse(task.taskDescription) else { return }
        // Replaced by a renewal: the download goes on in a new task with the same lease.
        if lock.withLock({ replaced.remove(task.taskIdentifier) != nil }) { return }
        releaseLease(info.id)
        lock.lock()
        meters[info.id] = nil
        refreshers[info.id] = nil
        measured[info.id] = nil
        renewalTask[info.id] = nil
        renewRejections[info.id] = nil
        lock.unlock()
        let id = info.id
        Task { @MainActor in DownloadSpeedStore.shared.clear(id) }
        guard let error else {
            Task { @MainActor in self.retryAttempts[id] = nil }
            return
        }
        let ns = error as NSError
        lock.lock()
        let intentional = pausing.remove(info.id) != nil
        let wasDeleted = deleted.contains(info.id)
        lock.unlock()
        if wasDeleted {
            // Never resurrect a deleted download's resume data.
            try? FileManager.default.removeItem(at: resumeFile(info.id))
            return
        }
        if let data = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            try? data.write(to: resumeFile(info.id))
        }
        if intentional && ns.code == NSURLErrorCancelled { return }
        if Self.isTransient(ns) {
            // Network dropped / timed out: don't fail, resume from where it stopped.
            downloads.onQueued(id: id)
            Task { @MainActor in self.scheduleAutoResume(id) }
            return
        }
        downloads.onFailed(id: info.id, message: ns.localizedDescription)
    }

    /// Re-issues the download (fresh credentials, saved resume data) with a growing delay.
    /// Background sessions wait for connectivity on their own, so the new task simply starts
    /// as soon as the network is back, even with the app suspended.
    @MainActor
    private func scheduleAutoResume(_ id: String) {
        let attempt = (retryAttempts[id] ?? 0) + 1
        retryAttempts[id] = attempt
        guard attempt <= Self.maxAutoRetries else {
            retryAttempts[id] = nil
            downloads.onFailed(id: id, message: "Connessione interrotta più volte. Tocca per riprovare.")
            return
        }
        let delay = UInt64(min(2 * attempt, 15)) * 1_000_000_000
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: delay)
            try? await self.downloads.resume(id: id)
        }
    }

    private static func isTransient(_ e: NSError) -> Bool {
        guard e.domain == NSURLErrorDomain else { return false }
        return [NSURLErrorNetworkConnectionLost, NSURLErrorNotConnectedToInternet, NSURLErrorTimedOut,
                NSURLErrorCannotConnectToHost, NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed,
                NSURLErrorDataNotAllowed, NSURLErrorInternationalRoamingOff, NSURLErrorCallIsActive,
                NSURLErrorBackgroundSessionWasDisconnected, NSURLErrorSecureConnectionFailed].contains(e.code)
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            Self.backgroundCompletionHandler?()
            Self.backgroundCompletionHandler = nil
        }
    }

    // MARK: Helpers

    private func resumeFile(_ id: String) -> URL {
        resumeDirectory.appendingPathComponent(id.replacingOccurrences(of: ":", with: "_") + ".resume")
    }

    private static func parse(_ description: String?) -> (id: String, fileName: String)? {
        guard let description, let sep = description.firstIndex(of: "|") else { return nil }
        return (String(description[..<sep]), String(description[description.index(after: sep)...]))
    }
}
#endif

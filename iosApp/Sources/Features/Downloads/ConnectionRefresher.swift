import Foundation

/// Keeps a download in the provider's "fast burst" phase (same logic as Android's
/// `ConnectionRefresher.kt`). Many streaming servers send each new connection at full speed for a
/// while, then throttle it; reopening from the same byte gets a fresh burst.
///
/// - Reactive: when the connection stays below half its own best speed for `slowWindows` seconds,
///   `onBytes` asks for a reconnection, and the burst (duration and bytes) is measured.
/// - Proactive: once the burst is known, the connection is renewed at `proactive` of it, before the
///   server slows down. Every `relearnEvery` proactive renewals one connection runs into the
///   throttle again to re-measure it.
///
/// One connection at a time (the caller cancels the old task before starting the new one).
/// Self-limiting: never more often than `minInterval`; renewals that don't help double the interval
/// and stop after `maxUseless`; refused reconnections lengthen the pause and stop after `maxRejections`.
/// Pure logic with an injectable clock (milliseconds). Not thread-safe: callers serialise access.
final class ConnectionRefresher {
    static let windowMs: Int64 = 1_000
    static let slowRatio = 0.5
    static let slowWindows = 3
    static let minIntervalMs: Int64 = 8_000
    static let proactive = 0.85
    static let relearnEvery = 5
    static let evalWindows = 3
    static let usefulGain = 1.5
    static let maxUseless = 3
    static let pauseMs: Int64 = 500
    static let maxPauseMs: Int64 = 5_000
    static let maxRejections = 3

    private let clock: () -> Int64

    // Current connection.
    private var connectedAt: Int64 = 0
    private var connBytes: Int64 = 0
    private var windowStart: Int64 = 0
    private var windowBytes: Int64 = 0
    private var windows = 0
    private var best = 0.0
    private var slow = 0
    private var slowStartAge: Int64 = 0
    private var slowStartBytes: Int64 = 0
    private var learnedHere = false

    // Learned across connections of this download.
    private var burstMs: Int64 = 0
    private var burstBytes: Int64 = 0
    private var throttledRate = 0.0
    private var evaluating = false
    private var useless = 0
    private var proactiveRun = 0
    private var rejections = 0

    private(set) var disabled = false
    private(set) var refreshes = 0
    private(set) var proactiveRefreshes = 0
    /// Pause between cancelling a connection and starting the next one.
    private(set) var pauseMs = ConnectionRefresher.pauseMs

    init(clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.clock = clock
    }

    /// Call when a new connection starts delivering data.
    func connected() {
        let now = clock()
        connectedAt = now
        connBytes = 0
        windowStart = now
        windowBytes = 0
        windows = 0
        best = 0
        slow = 0
        learnedHere = false
    }

    /// Call for every chunk received. Returns true when the connection should be renewed.
    func onBytes(_ n: Int64) -> Bool {
        if disabled { return false }
        let now = clock()
        let age = now - connectedAt
        connBytes += n
        windowBytes += n

        if burstMs > 0, proactiveRun < Self.relearnEvery, !evaluating, age >= minInterval,
           Double(age) >= Double(burstMs) * Self.proactive || Double(connBytes) >= Double(burstBytes) * Self.proactive {
            proactiveRun += 1
            proactiveRefreshes += 1
            return renew()
        }

        let elapsed = now - windowStart
        if elapsed < Self.windowMs { return false }
        let bytes = windowBytes
        let rate = Double(bytes) * 1000 / Double(elapsed)
        windowStart = now
        windowBytes = 0
        windows += 1
        if rate > best { best = rate }

        if evaluating && windows >= Self.evalWindows {
            evaluating = false
            if best < throttledRate * Self.usefulGain {
                // The new connection is as slow as the throttled one: renewing doesn't help.
                burstMs = 0
                burstBytes = 0
                useless += 1
                if useless >= Self.maxUseless { disabled = true; return false }
            } else {
                useless = 0
            }
        }

        // The first window includes TCP slow start: don't judge it.
        if windows > 1 && rate < best * Self.slowRatio {
            if slow == 0 { slowStartAge = age - elapsed; slowStartBytes = connBytes - bytes }
            slow += 1
        } else {
            slow = 0
        }
        if slow >= Self.slowWindows {
            if !learnedHere && slowStartAge > 0 {
                learnedHere = true
                burstMs = burstMs == 0 ? slowStartAge : (burstMs + slowStartAge) / 2
                burstBytes = burstBytes == 0 ? slowStartBytes : (burstBytes + slowStartBytes) / 2
                throttledRate = rate
            }
            if age >= minInterval {
                proactiveRun = 0
                return renew()
            }
        }
        return false
    }

    private func renew() -> Bool {
        refreshes += 1
        evaluating = throttledRate > 0
        return true
    }

    private var minInterval: Int64 { Self.minIntervalMs << Int64(useless) }

    /// The provider refused the reconnection (it may still count the connection just closed).
    func rejected() {
        pauseMs = min(pauseMs * 3, Self.maxPauseMs)
        rejections += 1
        if rejections >= Self.maxRejections { disabled = true }
    }

    func disable() { disabled = true }
}

package com.steplayer.app.download

/**
 * Keeps a download in the provider's "fast burst" phase. Many streaming servers send each new
 * connection at full speed for a while (a few seconds or a few MB), then throttle it. Closing the
 * connection and reopening it with a Range request from the same byte gets a fresh burst.
 *
 * - **Reactive**: when the current connection stays well below its own best speed for
 *   [SLOW_WINDOWS] seconds, [onBytes] asks for a reconnection. That also *measures* the burst
 *   (how long and how many bytes it lasted).
 * - **Proactive**: once the burst is known, the connection is renewed at [PROACTIVE] of it,
 *   before the server slows down, so the download stays almost always at full speed. Every
 *   [RELEARN_EVERY] proactive renewals one connection is left to run into the throttle again,
 *   to follow changes on the provider side.
 *
 * Still one connection at a time (the caller closes the old one before opening the new one), so
 * the provider's connection limit is respected. Self-limiting:
 * - never more often than every [minIntervalMs] per connection;
 * - if a renewal doesn't bring the speed back up (server simply slow, not throttling per
 *   connection) the burst is forgotten and the interval doubles; after [MAX_USELESS] useless
 *   renewals it stops;
 * - if the provider refuses the new connection ([rejected]) the pause before reconnecting grows,
 *   and after [MAX_REJECTIONS] refusals it stops.
 *
 * Pure logic with an injectable clock: unit-tested without a network.
 */
open class ConnectionRefresher(
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = WINDOW_MS,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    // Current connection.
    private var connectedAt = 0L
    private var connBytes = 0L
    private var windowStart = 0L
    private var windowBytes = 0L
    private var windows = 0
    private var best = 0.0
    private var slowWindows = 0
    private var slowStartAge = 0L
    private var slowStartBytes = 0L
    private var learnedHere = false

    // Learned across connections of this download.
    private var burstMs = 0L
    private var burstBytes = 0L
    private var throttledRate = 0.0
    private var evaluating = false
    private var useless = 0
    private var proactiveRun = 0
    private var rejections = 0

    var disabled = false
        private set
    var refreshes = 0
        private set
    var proactiveRefreshes = 0
        private set
    /** Pause between closing a connection and opening the next one. */
    var pauseMs = PAUSE_MS
        private set

    /** Call when a new connection starts delivering data. */
    open fun connected() {
        val now = clock()
        connectedAt = now
        connBytes = 0
        windowStart = now
        windowBytes = 0
        windows = 0
        best = 0.0
        slowWindows = 0
        learnedHere = false
    }

    /** Call after every read. Returns true when the connection should be closed and reopened. */
    open fun onBytes(n: Int): Boolean {
        if (disabled) return false
        val now = clock()
        val age = now - connectedAt
        connBytes += n
        windowBytes += n

        if (burstMs > 0 && proactiveRun < RELEARN_EVERY && !evaluating && age >= minInterval() &&
            (age >= burstMs * PROACTIVE || connBytes >= burstBytes * PROACTIVE)
        ) {
            proactiveRun++
            proactiveRefreshes++
            return renew()
        }

        val elapsed = now - windowStart
        if (elapsed < windowMs) return false
        val bytes = windowBytes
        val rate = bytes * 1000.0 / elapsed
        windowStart = now
        windowBytes = 0
        windows++
        if (rate > best) best = rate

        if (evaluating && windows >= EVAL_WINDOWS) {
            evaluating = false
            if (best < throttledRate * USEFUL_GAIN) {
                // The new connection is as slow as the throttled one: renewing doesn't help.
                burstMs = 0
                burstBytes = 0
                if (++useless >= MAX_USELESS) { disabled = true; return false }
            } else {
                useless = 0
            }
        }

        // The first window includes TCP slow start: don't judge it.
        if (windows > 1 && rate < best * SLOW_RATIO) {
            if (slowWindows == 0) { slowStartAge = age - elapsed; slowStartBytes = connBytes - bytes }
            slowWindows++
        } else {
            slowWindows = 0
        }
        if (slowWindows >= SLOW_WINDOWS) {
            if (!learnedHere && slowStartAge > 0) {
                learnedHere = true
                burstMs = if (burstMs == 0L) slowStartAge else (burstMs + slowStartAge) / 2
                burstBytes = if (burstBytes == 0L) slowStartBytes else (burstBytes + slowStartBytes) / 2
                throttledRate = rate
            }
            if (age >= minInterval()) {
                proactiveRun = 0
                return renew()
            }
        }
        return false
    }

    private fun renew(): Boolean {
        refreshes++
        evaluating = throttledRate > 0
        return true
    }

    private fun minInterval() = minIntervalMs shl useless

    /** The provider refused the reconnection (it may still count the connection just closed). */
    fun rejected() {
        pauseMs = (pauseMs * 3).coerceAtMost(MAX_PAUSE_MS)
        if (++rejections >= MAX_REJECTIONS) disabled = true
    }

    fun disable() { disabled = true }

    companion object {
        const val WINDOW_MS = 1_000L
        /** "Slow" = below this fraction of the connection's best window. */
        const val SLOW_RATIO = 0.5
        /** Consecutive slow windows (seconds) before reconnecting. */
        const val SLOW_WINDOWS = 3
        /** Never renew a connection younger than this. */
        const val MIN_INTERVAL_MS = 8_000L
        /** Renew at this fraction of the learned burst, before the server slows down. */
        const val PROACTIVE = 0.85
        /** After this many proactive renewals, let one connection run into the throttle again. */
        const val RELEARN_EVERY = 5
        /** Windows on the new connection used to judge whether the renewal helped. */
        const val EVAL_WINDOWS = 3
        /** A renewal helped if the new connection reached at least this multiple of the throttled speed. */
        const val USEFUL_GAIN = 1.5
        const val MAX_USELESS = 3
        const val PAUSE_MS = 500L
        const val MAX_PAUSE_MS = 5_000L
        const val MAX_REJECTIONS = 3
    }
}

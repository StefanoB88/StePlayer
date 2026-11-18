package com.steplayer.app.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRefresherTest {
    private var now = 0L
    private val refresher = ConnectionRefresher(clock = { now })

    /** Feeds [seconds] of data at [bytesPerSecond] in 250 ms reads; returns the elapsed ms when a renewal was asked, or null. */
    private fun feed(seconds: Int, bytesPerSecond: Int): Long? {
        val start = now
        repeat(seconds * 4) {
            now += 250
            if (refresher.onBytes(bytesPerSecond / 4)) return now - start
        }
        return null
    }

    /** A provider connection: 12 s at 4 MB/s, then throttled to 500 KB/s. Returns the connection age at renewal. */
    private fun throttledConnection(burstSeconds: Int = 12): Long? {
        refresher.connected()
        val fast = feed(burstSeconds, 4_000_000)
        if (fast != null) return fast
        return feed(120, 500_000)?.let { it + burstSeconds * 1000L }
    }

    @Test fun firstConnectionIsRenewedSoonAfterTheSlowdown() {
        // Slow from second 12; 3 slow seconds are enough.
        assertEquals(15_000L, throttledConnection())
        assertEquals(0, refresher.proactiveRefreshes)
    }

    @Test fun learnedBurstIsRenewedBeforeTheSlowdown() {
        throttledConnection()
        // Renewed at 85% of the 12 s burst, while still at full speed.
        repeat(ConnectionRefresher.RELEARN_EVERY) { assertEquals(10_250L, throttledConnection()) }
        assertEquals(ConnectionRefresher.RELEARN_EVERY, refresher.proactiveRefreshes)
        // Then one connection runs into the throttle again to re-measure the burst...
        assertEquals(15_000L, throttledConnection())
        // ...and proactive renewals resume.
        assertEquals(10_250L, throttledConnection())
        assertFalse(refresher.disabled)
    }

    @Test fun followsALongerBurstWhenRelearning() {
        throttledConnection(burstSeconds = 12)
        repeat(ConnectionRefresher.RELEARN_EVERY) { throttledConnection(burstSeconds = 20) }
        throttledConnection(burstSeconds = 20)           // re-measured: average of 12 and 20 s = 16 s
        assertEquals(13_750L, throttledConnection(burstSeconds = 20)) // 85% of 16 s, rounded up to the next read
    }

    @Test fun shortBurstStillWaitsTheMinimumInterval() {
        assertEquals(ConnectionRefresher.MIN_INTERVAL_MS, throttledConnection(burstSeconds = 3))
        assertEquals(ConnectionRefresher.MIN_INTERVAL_MS, throttledConnection(burstSeconds = 3))
    }

    @Test fun steadySpeedNeverRenews() {
        refresher.connected()
        assertEquals(null, feed(300, 1_000_000))
        assertEquals(0, refresher.refreshes)
    }

    @Test fun renewalsThatDoNotHelpBackOffThenStop() {
        // Server simply slow at times: a new connection starts as slow as the throttled one.
        val ages = mutableListOf<Long?>()
        ages += throttledConnection()                    // 15 s, burst learned
        repeat(3) {
            refresher.connected()
            val a = feed(3, 500_000)                     // judged useless: burst forgotten
                ?: feed(8, 4_000_000)?.plus(3_000)
                ?: feed(300, 500_000)?.plus(11_000)
            ages += a
        }
        // Minimum interval doubles (16 s, 32 s), then it stops.
        assertEquals(listOf<Long?>(15_000, 16_000, 32_000, null), ages)
        assertTrue(refresher.disabled)
    }

    @Test fun refusedReconnectionsSlowDownThenStop() {
        assertEquals(ConnectionRefresher.PAUSE_MS, refresher.pauseMs)
        refresher.rejected()
        assertEquals(1_500L, refresher.pauseMs)
        refresher.rejected()
        assertEquals(4_500L, refresher.pauseMs)
        assertFalse(refresher.disabled)
        refresher.rejected()
        assertEquals(ConnectionRefresher.MAX_PAUSE_MS, refresher.pauseMs)
        assertTrue(refresher.disabled)
        assertEquals(null, throttledConnection())
    }
}

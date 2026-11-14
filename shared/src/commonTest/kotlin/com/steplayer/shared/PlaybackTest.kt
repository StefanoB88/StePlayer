package com.steplayer.shared

import com.steplayer.shared.playback.AcquireResult
import com.steplayer.shared.playback.ConnectionGuard
import com.steplayer.shared.playback.LeaseKind
import com.steplayer.shared.playback.PlaybackErrorKind
import com.steplayer.shared.playback.PlaybackErrors
import com.steplayer.shared.playback.RetryPolicy
import com.steplayer.shared.playback.StallWatchdog
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionGuardTest {
    private fun guard(max: Int) = ConnectionGuard { max }

    @Test fun zappingRevokesPreviousPlaybackFirst() = runTest {
        val g = guard(1)
        val events = mutableListOf<String>()
        val first = assertIs<AcquireResult.Granted>(g.acquirePlayback(1, "Canale 1") { events += "stop ch1" }).lease
        val second = assertIs<AcquireResult.Granted>(g.acquirePlayback(1, "Canale 2") { events += "stop ch2" }).lease
        assertEquals(listOf("stop ch1"), events, "old channel must be closed when zapping")
        assertTrue(first.isReleased)
        assertFalse(second.isReleased)
        assertEquals(listOf("Canale 2"), g.active.value().map { it.label })
    }

    @Test fun releaseIsIdempotentAndFreesSlot() = runTest {
        val g = guard(1)
        val lease = assertIs<AcquireResult.Granted>(g.acquirePlayback(1, "Film") {}).lease
        lease.release(); lease.release()
        assertTrue(g.active.value().isEmpty())
        assertNotNull(g.acquireDownload(1, "Download"))
    }

    @Test fun downloadsNeverStarvePlaybackWhenBudgetAllows() = runTest {
        val g = guard(2)
        assertNotNull(g.acquireDownload(1, "D1"))
        assertNull(g.acquireDownload(1, "D2"), "one slot always reserved for playback")
        assertIs<AcquireResult.Granted>(g.acquirePlayback(1, "Live") {})
    }

    @Test fun singleConnectionBusyWithDownloadIsReported() = runTest {
        val g = guard(1)
        val d = assertNotNull(g.acquireDownload(1, "Film X"))
        val denied = assertIs<AcquireResult.Denied>(g.acquirePlayback(1, "Live") {})
        assertTrue(denied.canPauseDownloads)
        assertEquals(LeaseKind.DOWNLOAD, denied.busyWith.single().kind)
        d.release()
        assertIs<AcquireResult.Granted>(g.acquirePlayback(1, "Live") {})
        assertNull(g.acquireDownload(1, "Film Y"), "playback holds the only connection")
    }

    private fun <T : Any> com.steplayer.shared.util.CFlow<T>.value(): T =
        (this as kotlinx.coroutines.flow.Flow<T>).let { flow ->
            var v: T? = null
            runCatching { kotlinx.coroutines.runBlocking { flow.collect { v = it; throw StopCollect } } }
            v!!
        }

    private object StopCollect : RuntimeException()
}

class RetryAndStallTest {
    @Test fun maxConnectionsWaitsLongerAndGivesUp() {
        val p = RetryPolicy(isLive = true)
        val e = PlaybackErrors.fromHttpStatus(458, maxConnections = 1)
        assertEquals(PlaybackErrorKind.MAX_CONNECTIONS, e.kind)
        assertTrue(e.message.contains("1 connessione"))
        assertEquals(listOf(4_000L, 10_000L, 20_000L, null), List(4) { p.nextDelayMs(e) })
    }

    @Test fun nonRetryableStopsImmediately() {
        assertNull(RetryPolicy(true).nextDelayMs(PlaybackErrors.fromHttpStatus(404)))
        assertNull(RetryPolicy(false).nextDelayMs(PlaybackErrors.unsupported("avi")))
    }

    @Test fun watchdogDetectsFrozenLive() {
        val w = StallWatchdog(isLive = true)
        assertFalse(w.tick(0, 1_000, true))
        assertFalse(w.tick(5_000, 6_000, true))     // advancing
        assertFalse(w.tick(10_000, 6_000, true))    // stuck 5s
        assertFalse(w.tick(10_000, 6_000, false))   // user paused → never stalled
        assertFalse(w.tick(20_000, 6_000, true))    // resumed: timer restarted at 10s
        assertTrue(w.tick(22_000, 6_000, true))     // stuck 12s
    }
}

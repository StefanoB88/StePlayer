package com.steplayer.shared

import com.steplayer.shared.playback.AcquireResult
import com.steplayer.shared.playback.ConnectionGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Players release on the main thread while download threads acquire/release: the books must stay exact. */
class ConnectionGuardConcurrencyTest {
    @Test fun concurrentAcquireAndReleaseKeepConsistentState() = runBlocking {
        val g = ConnectionGuard { 4 }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val jobs = (1..8).map { worker ->
                async(Dispatchers.Default) {
                    repeat(2_000) { i ->
                        if (worker % 2 == 0) {
                            val lease = g.acquireDownload(1, "D$worker-$i")
                            // release from a different thread, like a download worker finishing
                            if (lease != null) pool.submit { lease.release() }.get()
                        } else {
                            val r = g.acquirePlayback(1, "P$worker-$i") {}
                            if (r is AcquireResult.Granted) pool.submit { r.lease.release() }.get()
                            g.hasPlayback()
                        }
                    }
                }
            }
            jobs.awaitAll()
        } finally { pool.shutdown() }
        assertTrue(g.active.first().isEmpty(), "every lease released → nothing left: ${g.active.first()}")
        assertEquals(false, g.hasPlayback())
    }
}

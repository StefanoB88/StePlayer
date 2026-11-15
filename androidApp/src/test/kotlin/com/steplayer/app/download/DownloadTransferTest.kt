package com.steplayer.app.download

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class DownloadTransferTest {
    private val tmp = TemporaryFolder().apply { create() }
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build()
    private val data = Random(42).nextBytes(8 * 1024 * 1024)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** Server behaviour knobs. */
    private var supportsRanges = true
    private var bytesPer100ms = 0L          // per-connection throttle (0 = unlimited)
    private val disconnectNext = AtomicInteger(0)

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.path != "/movie.mkv") return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")?.removePrefix("bytes=")
                val response = if (supportsRanges && range != null) {
                    val start = range.substringBefore('-').toLong()
                    val end = range.substringAfter('-').toLongOrNull() ?: (data.size - 1L)
                    if (start >= data.size) return MockResponse().setResponseCode(416)
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${data.size}")
                        .setBody(Buffer().write(data, start.toInt(), (end - start + 1).toInt()))
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(data))
                }
                if (bytesPer100ms > 0) response.throttleBody(bytesPer100ms, 100, TimeUnit.MILLISECONDS)
                if (disconnectNext.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                    response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
                }
                return response
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun transfer(file: File = File(tmp.root, "movie.mkv")) =
        DownloadTransfer(client, server.url("/movie.mkv").toString(), mapOf("User-Agent" to "Test"), file, minSegmentBytes = 1024 * 1024)

    @Test fun parallelSegmentsAreCorrectAndMuchFaster() = runBlocking {
        bytesPer100ms = 256 * 1024 // ~2.5 MB/s per connection, like a throttling streaming server
        val single = File(tmp.root, "single.mkv")
        val t1 = System.nanoTime(); transfer(single).run(1) { _, _ -> }; val oneConn = System.nanoTime() - t1
        val multi = File(tmp.root, "multi.mkv")
        val t2 = System.nanoTime(); transfer(multi).run(4) { _, _ -> }; val fourConn = System.nanoTime() - t2
        assertArrayEquals(data, single.readBytes())
        assertArrayEquals(data, multi.readBytes())
        assertTrue("4 connections (${fourConn / 1_000_000} ms) should be much faster than 1 (${oneConn / 1_000_000} ms)", fourConn < oneConn * 0.6)
        assertTrue(!File(multi.path + ".state").exists())
    }

    @Test fun neverUsesMoreConnectionsThanGranted() = runBlocking {
        transfer().run(2) { _, _ -> }
        val ranged = requests.filter { it.getHeader("Range") != null }
        assertEquals("probe + 1 extra segment", 2, ranged.size)
        assertTrue(requests.all { it.getHeader("Accept-Encoding") == "identity" })
    }

    @Test fun reportsTheConnectionsReallyUsed() = runBlocking {
        val reported = mutableListOf<Int>()
        // 8 MB with 1 MB minimum segments: all 4 granted connections are used.
        transfer(File(tmp.root, "big.mkv")).run(4, onConnections = { reported += it }) { _, _ -> }
        // Small file (one segment): only 1 of the 4 granted connections, so the caller can free the others.
        DownloadTransfer(client, server.url("/movie.mkv").toString(), emptyMap(), File(tmp.root, "small.mkv"), minSegmentBytes = 16L * 1024 * 1024)
            .run(4, onConnections = { reported += it }) { _, _ -> }
        // Server without ranges: one sequential stream.
        supportsRanges = false
        transfer(File(tmp.root, "seq.mkv")).run(4, onConnections = { reported += it }) { _, _ -> }
        assertEquals(listOf(4, 1, 1), reported)
    }

    @Test fun reconnectsSegmentAfterDroppedConnection() = runBlocking {
        disconnectNext.set(2)
        val file = File(tmp.root, "drop.mkv")
        transfer(file).run(3) { _, _ -> }
        assertArrayEquals(data, file.readBytes())
        // A dropped segment must be re-requested from the exact byte where it stopped (not from its start).
        val starts = requests.mapNotNull { it.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLong() }
        val segmentStarts = setOf(0L, 2796202L, 5592404L)
        assertTrue("mid-segment resume expected: $starts", starts.any { it !in segmentStarts })
    }

    @Test fun resumesAfterPauseOrProcessDeath() = runBlocking {
        bytesPer100ms = 256 * 1024
        val file = File(tmp.root, "resume.mkv")
        val progress = mutableListOf<Long>()
        // "Pause" after ~1.5 s.
        val result = withTimeoutOrNull(1_500) { transfer(file).run(2) { b, _ -> progress += b } }
        assertEquals(null, result)
        assertTrue("resume state saved", File(file.path + ".state").exists())
        val before = requests.size
        bytesPer100ms = 0
        transfer(file).run(2) { _, _ -> }
        assertArrayEquals(data, file.readBytes())
        val resumed = requests.drop(before).mapNotNull { it.getHeader("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toLong() }
        assertTrue("continues from saved offsets, not from zero: $resumed", resumed.none { it == 0L })
    }

    @Test fun serverWithoutRangeSupportStillWorks() = runBlocking {
        supportsRanges = false
        val file = File(tmp.root, "norange.mkv")
        var lastTotal = 0L
        transfer(file).run(4) { _, total -> lastTotal = total }
        assertArrayEquals(data, file.readBytes())
        assertEquals(data.size.toLong(), lastTotal)
        assertEquals(1, requests.size)
    }

    @Test fun legacyPartialFileIsContinued() = runBlocking {
        val file = File(tmp.root, "legacy.mkv")
        file.writeBytes(data.copyOfRange(0, 3_000_000))
        transfer(file).run(4) { _, _ -> }
        assertArrayEquals(data, file.readBytes())
        assertEquals("bytes=3000000-", requests.first().getHeader("Range"))
    }

    @Test(expected = HttpStatusException::class)
    fun notFoundIsReported() = runBlocking<Unit> {
        DownloadTransfer(client, server.url("/missing").toString(), emptyMap(), File(tmp.root, "x")).run(1) { _, _ -> }
    }
}

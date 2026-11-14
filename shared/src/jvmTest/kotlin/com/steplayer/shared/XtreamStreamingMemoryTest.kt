package com.steplayer.shared

import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.xtream.VodStreamDto
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamCredentials
import com.steplayer.shared.xtream.XtreamException
import com.steplayer.shared.xtream.XtreamJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Peak heap while decoding a synthetic 50,000-title get_vod_streams response: streaming vs whole-body String. */
class XtreamStreamingMemoryTest {
    private val items = 50_000

    private fun item(i: Int) = """{"num":$i,"name":"Pellicola di prova numero $i (2020)","stream_type":"movie","stream_id":$i,""" +
        """"stream_icon":"http://img.example/poster/$i.jpg","rating":"7.2","rating_5based":3.6,"added":"1700000000",""" +
        """"category_id":"${i % 40}","container_extension":"mkv","custom_sid":"","direct_source":"",""" +
        """"plot":"Una trama completamente inventata per il titolo numero $i, scritta solo per misurare la memoria."}"""

    @OptIn(DelicateCoroutinesApi::class)
    private fun server(body: (Int) -> String = ::item, count: Int = items) = HttpClient(MockEngine {
        // Generated on the fly: the test server itself never holds the whole payload.
        val content = GlobalScope.writer(Dispatchers.IO) {
            channel.writeStringUtf8("[")
            for (i in 1..count) channel.writeStringUtf8((if (i > 1) "," else "") + body(i))
            channel.writeStringUtf8("]")
        }.channel
        respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { stePlayerDefaults() }

    private val creds = XtreamCredentials("http://srv.example", "utente", "segreto")

    /** The previous implementation: whole body as a String, then decode. */
    private suspend fun decodeWholeBody(http: HttpClient): List<VodStreamDto> {
        val text = http.get("http://srv.example/player_api.php?action=get_vod_streams").bodyAsText().trim()
        return XtreamJson.decodeFromString(ListSerializer(VodStreamDto.serializer()), text)
    }

    private class Peak(val sampledMb: Double, val oldGenMb: Double)

    /** Runs [block] while sampling used heap every ~0.2 ms; also reports the old-gen pool peak (humongous allocations land there). */
    private fun measure(block: () -> List<VodStreamDto>): Peak {
        val mem = ManagementFactory.getMemoryMXBean()
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        val oldPools = pools.filter { it.name.contains("Old", true) || it.name.contains("Tenured", true) }
        repeat(3) { System.gc(); Thread.sleep(50) }
        val baseline = mem.heapMemoryUsage.used
        val oldBaseline = oldPools.sumOf { it.usage.used }
        pools.forEach { it.resetPeakUsage() }
        val running = AtomicBoolean(true)
        val peak = AtomicLong(baseline)
        val sampler = thread(isDaemon = true) {
            while (running.get()) {
                peak.accumulateAndGet(mem.heapMemoryUsage.used, ::maxOf)
                Thread.sleep(0, 200_000)
            }
        }
        val result = block()
        running.set(false)
        sampler.join()
        val oldPeak = oldPools.sumOf { it.peakUsage.used }
        assertEquals(items, result.size)
        return Peak((peak.get() - baseline) / 1e6, (oldPeak - oldBaseline) / 1e6)
    }

    @Test fun streamingDecodeUsesLessPeakMemory() {
        val http = server()
        val client = XtreamClient(http, creds, null)
        // Warm-up (class loading, JIT) so both runs are measured hot.
        runBlocking { client.vodStreams(); decodeWholeBody(http) }

        val before = (1..2).map { measure { runBlocking { decodeWholeBody(http) } } }
        val after = (1..2).map { measure { runBlocking { client.vodStreams() } } }
        val b = before.minBy { it.sampledMb }
        val a = after.minBy { it.sampledMb }
        println(
            "50k VOD decode peak heap above baseline: whole-body String = %.1f MB (old gen %.1f MB), streaming = %.1f MB (old gen %.1f MB)"
                .format(b.sampledMb, b.oldGenMb, a.sampledMb, a.oldGenMb),
        )
        assertTrue(a.sampledMb < b.sampledMb, "streaming peak ${a.sampledMb} MB must be below ${b.sampledMb} MB")
        assertTrue(a.oldGenMb < b.oldGenMb, "no humongous body/String allocations when streaming")
    }

    @Test fun streamingKeepsBehaviourOfOldDecoder() = runBlocking {
        val list = XtreamClient(server(count = 3), creds, null).vodStreams()
        assertEquals(listOf("1", "2", "3"), list.map { it.streamId })
        assertEquals("Pellicola di prova numero 2 (2020)", list[1].name)
        assertEquals(7.2, list[0].rating)

        // Servers answering {} / null / empty for "no items".
        for (body in listOf("{}", "null", "", "[]")) {
            val http = HttpClient(MockEngine { respond(body) }) { stePlayerDefaults() }
            assertTrue(XtreamClient(http, creds, null).vodStreams().isEmpty(), "body=<$body>")
        }
        // A broken body is a PARSE error, never an empty catalog (which would wipe the local copy).
        for (body in listOf("<html>Manutenzione</html>", """[{"name":"a"},{"name":""", """[{"name":"a"}, 12x]""")) {
            val http = HttpClient(MockEngine { respond(body) }) { stePlayerDefaults() }
            val e = assertFailsWith<XtreamException> { XtreamClient(http, creds, null).vodStreams() }
            assertEquals(XtreamException.Kind.PARSE, e.kind, "body=<$body>")
        }
    }
}

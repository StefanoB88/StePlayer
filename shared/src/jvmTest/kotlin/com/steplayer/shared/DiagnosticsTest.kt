package com.steplayer.shared

import com.steplayer.shared.diagnostics.ConnectionVerdict
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.settings.BufferMode
import com.steplayer.shared.settings.PlayerPreferences
import com.steplayer.shared.settings.SubtitleMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiagnosticsTest {
    private val m3u = """#EXTM3U
        #EXTINF:-1,Canale Uno
        http://prov/live/uno.m3u8
        #EXTINF:-1,Canale Due
        http://prov/live/due.ts""".trimIndent()

    private fun core(streamStatus: HttpStatusCode = HttpStatusCode.OK): StePlayerCore {
        val requested = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            val u = req.url.toString(); requested += u
            when {
                u.startsWith("http://pl/") -> respond(m3u)
                u == "http://prov/live/uno.m3u8" -> respond("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=5000000\nhd/index.m3u8\n")
                u == "http://prov/live/hd/index.m3u8" -> respond("#EXTM3U\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.ts\n")
                u == "http://prov/live/hd/seg2.ts" -> respond(ByteArray(3_000_000), streamStatus)
                u.startsWith("http://prov/live/due.ts") -> respond(ByteArray(2_000_000), streamStatus)
                u.startsWith("https://ref/") -> respond(ByteArray(4_000_000))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }) { stePlayerDefaults() }
        return StePlayerCore(JvmPlatform(http))
    }

    @Test fun hlsChannelIsMeasuredThroughItsSegmentAndConnectionClosed() = runBlocking {
        val core = core()
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/x.m3u"))
        core.sync.sync(id)
        val r = core.connectionTest.run(id, referenceUrl = "https://ref/100mb")
        assertEquals(ConnectionVerdict.GOOD, r.verdict, r.message)
        assertEquals("Canale Uno", r.testedChannelName)
        assertTrue((r.providerMbps ?: 0.0) > 0 && (r.internetMbps ?: 0.0) > 0)
        assertTrue(core.playback.connections.active.first().isEmpty(), "test lease must be released")
    }

    @Test fun maxConnectionsIsExplained() = runBlocking {
        val core = core(HttpStatusCode(458, "Max connections"))
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/x.m3u"))
        core.sync.sync(id)
        val ch = core.catalog.channels(id, "__all__").first().first { it.name == "Canale Due" }
        val r = core.connectionTest.run(id, ch, referenceUrl = null)
        assertEquals(ConnectionVerdict.PROVIDER_ERROR, r.verdict)
        assertEquals("Troppe connessioni", r.title)
        assertTrue(core.playback.connections.active.first().isEmpty())
    }

    @Test fun preferencesPersistAndDelaysAreClamped() = runBlocking {
        val core = core()
        val prefs = core.playerPreferences
        assertEquals(4, prefs.current().stillWatchingAfterHours)
        assertEquals(BufferMode.LARGE, prefs.current().bufferMode, "default buffer is Grande")
        prefs.update { it.copy(bufferMode = BufferMode.LARGE, subtitleMode = SubtitleMode.ALWAYS, preferredAudioLanguages = listOf("en")) }
        val s = prefs.settings.first()
        assertEquals(BufferMode.LARGE, s.bufferMode); assertEquals(listOf("en"), s.preferredAudioLanguages)
        val key = PlayerPreferences.contentKey(1, "LIVE", "42")
        prefs.setAudioDelayMs(key, 9_999)
        assertEquals(3_000, prefs.audioDelayMs(key))
        prefs.setAudioDelayMs(key, 0)
        assertEquals(0, prefs.audioDelayMs(key))
    }
}

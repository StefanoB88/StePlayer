package com.steplayer.shared

import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.pairing.SendToTvServer
import com.steplayer.shared.pairing.SendToTvState
import com.steplayer.shared.platform.JvmPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendToTvServerTest {

    private fun request(url: String, body: String? = null): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 10000
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/plain")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val stream = if (code < 400) c.inputStream else c.errorStream
        return code to (stream?.bufferedReader()?.readText() ?: "")
    }

    @Test fun tvReceivesTheSelectedListWithTheCode() = runBlocking {
        val core = StePlayerCore(JvmPlatform())
        val id = core.sources.add(SourceInput.M3u("Casa", "https://server.esempio/lista.m3u?token=segreto", "https://server.esempio/guida.xml"))
        val code = core.sendToTv.start(listOf(id), device = "Telefono di prova") ?: return@runBlocking // offline machine
        val waiting = core.sendToTv.state.first { it is SendToTvState.Waiting } as SendToTvState.Waiting
        val base = "http://127.0.0.1:" + waiting.address.substringAfterLast(':')
        assertTrue(waiting.address.substringAfterLast(':').toInt() in SendToTvServer.PORTS)

        val (hc, hello) = request(base + SendToTvServer.HELLO)
        assertEquals(200, hc)
        assertTrue("\"app\":\"StePlayer\"" in hello && "Telefono di prova" in hello)
        assertTrue("segreto" !in hello, "hello must not leak secrets")

        val wrong = if (code == "1234") "4321" else "1234"
        val (wc, wbody) = request(base + SendToTvServer.CLAIM, """{"code":"$wrong"}""")
        assertEquals(403, wc); assertTrue("segreto" !in wbody)

        val (cc, claim) = request(base + SendToTvServer.CLAIM, """{"code":"$code"}""")
        assertEquals(200, cc)
        assertTrue("\"kind\":\"m3u\"" in claim && "token=segreto" in claim && "guida.xml" in claim && "Casa" in claim)
        assertEquals(SendToTvState.Sent(1), core.sendToTv.state.first { it is SendToTvState.Sent })

        // Single use.
        Thread.sleep(800)
        val again = runCatching { request(base + SendToTvServer.CLAIM, """{"code":"$code"}""").first }.getOrDefault(-1)
        assertTrue(again == 410 || again == -1, "must not be reusable (got $again)")
        core.sendToTv.stop()
    }

    @Test fun fiveWrongCodesStopTheServer() = runBlocking {
        val core = StePlayerCore(JvmPlatform())
        val id = core.sources.add(SourceInput.M3u("Casa", "https://server.esempio/lista.m3u"))
        val code = core.sendToTv.start(listOf(id)) ?: return@runBlocking
        val waiting = core.sendToTv.state.first { it is SendToTvState.Waiting } as SendToTvState.Waiting
        val base = "http://127.0.0.1:" + waiting.address.substringAfterLast(':')
        val wrong = if (code == "9999") "8888" else "9999"
        repeat(5) { request(base + SendToTvServer.CLAIM, """{"code":"$wrong"}""") }
        val failed = core.sendToTv.state.first { it is SendToTvState.Failed } as SendToTvState.Failed
        assertTrue("Troppi codici" in failed.message)
        Thread.sleep(500)
        val after = runCatching { request(base + SendToTvServer.CLAIM, """{"code":"$code"}""").first }.getOrDefault(-1)
        assertTrue(after != 200, "the right code must not work after the lock-out")
        core.sendToTv.stop()
    }
}

package com.steplayer.shared

import com.steplayer.shared.model.SourceType
import com.steplayer.shared.pairing.PairingState
import com.steplayer.shared.platform.JvmPlatform
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairingServerTest {

    private fun request(url: String, body: String? = null): Pair<Int, String> {
        val c = URI(url).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 10000
        if (body != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream).bufferedReader().readText()
        return code to text
    }

    private fun form(vararg kv: Pair<String, String>) =
        kv.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    @Test fun phoneAddsListToTv() = runBlocking {
        val core = StePlayerCore(JvmPlatform())
        val url = core.pairing.start(preferredPort = 0) ?: return@runBlocking // no network on this machine
        // Talk to it over loopback (always "private") on the advertised port.
        val local = url.replace(Regex("http://[^:]+:"), "http://127.0.0.1:")
        val token = local.substringAfterLast('/')
        assertEquals(26, token.length)

        val (code, page) = request(local)
        assertEquals(200, code)
        assertTrue("Accesso al server" in page && "Lista M3U" in page)
        assertTrue("Xtream" !in page, "legal: no third-party names in the UI")
        assertTrue("Xtream Codes" !in page, "legal: never mention Xtream Codes")
        assertTrue("<script" !in page)

        assertEquals(404, request(local.replace(token, "wrongtokenwrongtokenwrongt")).first)

        // Missing fields → form again with error
        val (c2, p2) = request(local, form("type" to "xtream", "server" to "", "username" to "", "password" to ""))
        assertEquals(400, c2); assertTrue("Compila tutti i campi" in p2)

        // Valid M3U → added (no network needed to validate M3U)
        val (c3, p3) = request(local, form("type" to "m3u", "name" to "Casa", "url" to "https://server.esempio/lista.m3u?token=x"))
        assertEquals(200, c3); assertTrue("Fatto" in p3)
        val state = core.pairing.state.first { it is PairingState.Added } as PairingState.Added
        val source = core.sources.byId(state.sourceId)!!
        assertEquals("Casa", source.name); assertEquals(SourceType.M3U, source.type)

        // Single use: the link is dead now (server stops shortly after success)
        Thread.sleep(800)
        val reused = runCatching { request(local, form("type" to "m3u", "url" to "https://x/y.m3u")).first }.getOrDefault(-1)
        assertTrue(reused == 404 || reused == -1, "link must not be reusable (got $reused)")
        core.pairing.stop()
    }

    @Test fun htmlIsEscaped() = runBlocking {
        val core = StePlayerCore(JvmPlatform())
        val url = core.pairing.start(preferredPort = 0) ?: return@runBlocking
        val local = url.replace(Regex("http://[^:]+:"), "http://127.0.0.1:")
        val (_, page) = request(local, form("type" to "m3u", "name" to "<img src=x onerror=alert(1)>", "url" to "non-un-url"))
        assertTrue("<img src=x" !in page && "&lt;img" in page)
        core.pairing.stop()
    }
}

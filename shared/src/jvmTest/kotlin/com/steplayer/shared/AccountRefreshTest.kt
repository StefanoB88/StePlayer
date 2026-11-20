package com.steplayer.shared

import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.stePlayerDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** The expiry date follows renewals made at the provider without a full catalog sync. */
class AccountRefreshTest {
    private var expSec = 1_900_000_000L
    private var maxConnections = 1
    private var down = false
    private var logins = 0

    private fun server() = HttpClient(MockEngine { req ->
        if (down) return@MockEngine respond("", HttpStatusCode.ServiceUnavailable)
        if (req.url.parameters["action"] == null) logins++
        val body = if (req.url.parameters["action"] == null) {
            """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"$expSec","max_connections":"$maxConnections","allowed_output_formats":["m3u8"]},
               "server_info":{"timezone":"UTC","timestamp_now":1705339800,"time_now":"2024-01-15 17:30:00"}}"""
        } else "[]"
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }) { stePlayerDefaults() }

    @Test fun expiryFollowsRenewalsWithOneLogin() = runBlocking {
        val core = StePlayerCore(JvmPlatform(server()))
        val id = core.sources.add(SourceInput.Xtream("Test", "srv.example:8080", "u", "p"))

        core.sync.refreshAccount(id, 0)
        assertEquals(expSec * 1000, core.sources.byId(id)!!.expiresAt)

        // Renewed at the provider: one login request updates expiry and connections, no catalog download.
        expSec += 30 * 24 * 3600
        maxConnections = 2
        val before = logins
        core.sync.refreshAccount(id, 0)
        assertEquals(before + 1, logins)
        assertEquals(expSec * 1000, core.sources.byId(id)!!.expiresAt)
        assertEquals(2, core.sources.byId(id)!!.maxConnections)
    }

    @Test fun throttledAndQuietOnFailure() = runBlocking {
        val core = StePlayerCore(JvmPlatform(server()))
        val id = core.sources.add(SourceInput.Xtream("Test", "srv.example:8080", "u", "p"))
        core.sync.refreshAccount(id, 0)
        val known = core.sources.byId(id)!!.expiresAt

        // Within the minimum interval: no request.
        val before = logins
        core.sync.refreshAccount(id, 60_000)
        assertEquals(before, logins)

        // Server down: no exception, previous values kept.
        down = true
        core.sync.refreshAccount(id, 0)
        assertEquals(known, core.sources.byId(id)!!.expiresAt)
    }

    @Test fun m3uListsAreSkipped() = runBlocking {
        val core = StePlayerCore(JvmPlatform(HttpClient(MockEngine { respond("#EXTM3U\n#EXTINF:-1,Uno\nhttp://x/1.ts\n", HttpStatusCode.OK) }) { stePlayerDefaults() }))
        val id = core.sources.add(SourceInput.M3u("Lista", "http://lists.example/my.m3u"))
        core.sync.refreshAccount(id, 0) // nothing to refresh, no error
        assertEquals(null, core.sources.byId(id)!!.expiresAt)
    }
}

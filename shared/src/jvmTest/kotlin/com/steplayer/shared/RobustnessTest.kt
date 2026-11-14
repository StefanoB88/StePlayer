package com.steplayer.shared

import com.steplayer.shared.data.CatchupTemplates
import com.steplayer.shared.data.Categories
import com.steplayer.shared.model.ChannelWithEpg
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.model.SyncState
import com.steplayer.shared.model.SyncStep
import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.xtream.flexInt
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RobustnessTest {
    private val nowSec = System.currentTimeMillis() / 1000
    private fun t(sec: Long): String = java.text.SimpleDateFormat("yyyyMMddHHmmss Z")
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(sec * 1000))

    private val m3u = """#EXTM3U x-tvg-url="http://epg/guide.xml"
        #EXTINF:-1 tvg-id="uno" group-title="Prova",Canale Uno
        http://cdn/uno.m3u8
        #EXTINF:-1 tvg-id="due" group-title="Prova",Canale Due
        http://cdn/due.m3u8""".trimIndent()
    private val guide = """<tv>
        <programme start="${t(nowSec - 600)}" stop="${t(nowSec + 1200)}" channel="uno"><title>Mattina Inventata</title></programme>
        <programme start="${t(nowSec - 600)}" stop="${t(nowSec + 1200)}" channel="due"><title>Rubrica Finta</title></programme>
        </tv>"""

    private suspend fun setup(epgHangs: Boolean = false): Triple<StePlayerCore, CapturingPlatform, Long> {
        val http = HttpClient(MockEngine { req ->
            if (req.url.host == "epg") {
                if (epgHangs) awaitCancellation()
                respond(guide)
            } else respond(m3u)
        }) { stePlayerDefaults() }
        val platform = CapturingPlatform(JvmPlatform(http))
        val core = StePlayerCore(platform)
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/list.m3u"))
        if (!epgHangs) core.sync.sync(id)
        return Triple(core, platform, id)
    }

    @Test fun nowNextHandlesTensOfThousandsOfChannels() = runBlocking {
        val (core, platform, id) = setup()
        // Same variable limit as SQLite on Android 8 (999 per statement).
        limitSqlVariables(platform.driver as JdbcSqliteDriver, 999)
        val real = core.catalog.channels(id, Categories.ALL).first()
        // 40,000 distinct epg ids. Previously one IN (...) with all of them: "too many SQL variables".
        val fake = (1..40_000).map { real.first().copy(id = "f$it", epgId = "fittizio$it") }
        val all = fake + real
        val result = core.epg.nowNext(id, all).first()
        assertEquals(all.size, result.size)
        assertEquals(listOf("Mattina Inventata", "Rubrica Finta"), result.takeLast(2).map { it.now?.title })
        assertTrue(result.dropLast(2).all { it.now == null })
    }

    @Test fun failingQueryEmitsChannelsWithoutGuideInsteadOfCrashing() = runBlocking {
        val (core, platform, id) = setup()
        val channels = core.catalog.channels(id, Categories.ALL).first()
        platform.driver.close() // every query now throws
        val emitted = withTimeout(10_000) { core.epg.nowNext(id, channels).toList() }
        assertEquals(listOf(channels.map { ChannelWithEpg(it, null, null) }), emitted)
        // Platform download callbacks must not throw into native code either.
        core.downloads.onProgress("1:MOVIE:x", 10, 100)
        core.downloads.onCompleted("1:MOVIE:x", "x.mp4")
        core.downloads.onQueued("1:MOVIE:x")
        core.downloads.onFailed("1:MOVIE:x", "rete assente")
        // And a CFlow over a dead DB completes instead of throwing.
        assertEquals(emptyList(), withTimeout(10_000) { core.catalog.channels(id, Categories.ALL).toList() })
    }

    @Test fun cancellingSyncDuringEpgIsNotReportedAsDone() = runBlocking {
        val (core, _, id) = setup(epgHangs = true)
        val job = launch(Dispatchers.Default) { core.sync.sync(id) }
        withTimeout(10_000) {
            while ((core.sync.state.first() as? SyncState.Running)?.step != SyncStep.EPG) delay(10)
        }
        delay(100) // inside the EPG download now
        job.cancel()
        job.join()
        // Before: runCatching swallowed the CancellationException and the state became Done.
        assertEquals(SyncState.Idle, core.sync.state.first())
        assertTrue(job.isCancelled)
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Test fun cancellingM3uSyncMidDownloadRollsBackAndStops() = runBlocking {
        val calls = AtomicInteger()
        val http = HttpClient(MockEngine { req ->
            when {
                req.url.host == "epg" -> respond("<tv></tv>")
                calls.incrementAndGet() == 1 -> respond(m3u)
                else -> respond(GlobalScope.writer(Dispatchers.IO) {
                    // Half a list, then the server stalls.
                    channel.writeStringUtf8("#EXTM3U\n#EXTINF:-1 group-title=\"Nuovo\",Canale Nuovo\nhttp://cdn/nuovo.m3u8\n")
                    channel.flush()
                    awaitCancellation()
                }.channel)
            }
        }) { stePlayerDefaults() }
        val core = StePlayerCore(JvmPlatform(http))
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/list.m3u"))
        core.sync.sync(id)
        val job = launch(Dispatchers.Default) { core.sync.sync(id) }
        delay(500)
        job.cancel()
        withTimeout(5_000) { job.join() } // the blocking line reader stops with the sync
        assertEquals(listOf("Canale Uno", "Canale Due"), core.catalog.channels(id, Categories.ALL).first().map { it.name })
        assertEquals(SyncState.Idle, core.sync.state.first())
    }

    @Test fun timeMathNeverOverflowsOrDividesByZero() {
        val start = 1_700_000_000_000L
        // {duration:0} used to throw ArithmeticException.
        assertEquals("?d=3600", CatchupTemplates.fill("?d={duration:0}", start, start + 3_600_000))
        assertEquals("?d=60", CatchupTemplates.fill("?d={duration:60}", start, start + 3_600_000))
        // Out-of-range numbers from servers become null instead of wrapping to negative values.
        assertNull(JsonPrimitive("99999999999").flexInt())
        assertEquals(42, JsonPrimitive("42").flexInt())
    }
}

/** sqlite-jdbc is only on the runtime classpath: SQLiteConnection.setLimit via reflection. */
private fun limitSqlVariables(driver: JdbcSqliteDriver, max: Int) {
    val connClass = Class.forName("org.sqlite.SQLiteConnection")
    val limits = Class.forName("org.sqlite.SQLiteLimits")
    val variableLimit = limits.enumConstants.first { (it as Enum<*>).name == "SQLITE_LIMIT_VARIABLE_NUMBER" }
    val conn = driver.getConnection().unwrap(connClass)
    connClass.getMethod("setLimit", limits, Int::class.javaPrimitiveType).invoke(conn, variableLimit, max)
}

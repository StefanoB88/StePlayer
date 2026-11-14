package com.steplayer.shared

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import com.steplayer.shared.data.Categories
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.PlatformDependencies
import com.steplayer.shared.platform.stePlayerDefaults
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Platform that exposes the SQL driver so tests can count table notifications. */
internal class CapturingPlatform(private val base: JvmPlatform) : PlatformDependencies by base {
    lateinit var driver: SqlDriver
    override fun createSqlDriver(): SqlDriver = base.createSqlDriver().also { driver = it }
}

internal fun SqlDriver.countNotifications(vararg tables: String): Map<String, AtomicInteger> =
    tables.associateWith { table ->
        AtomicInteger().also { counter -> addListener(table, listener = Query.Listener { counter.incrementAndGet() }) }
    }

class NotificationStormTest {
    private val nowSec = System.currentTimeMillis() / 1000
    private fun t(sec: Long): String = java.text.SimpleDateFormat("yyyyMMddHHmmss Z")
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(sec * 1000))

    private val channels = 60
    private val m3u = "#EXTM3U x-tvg-url=\"http://epg/guide.xml\"\n" + (1..channels).joinToString("\n") { i ->
        "#EXTINF:-1 tvg-id=\"ch$i\" group-title=\"Gruppo ${i % 4}\",Canale Prova $i\nhttp://cdn/ch$i.m3u8"
    } + "\n#EXTINF:-1 group-title=\"Film\",Pellicola Inventata (2021)\nhttp://cdn/vod/f1.mp4" +
        "\n#EXTINF:-1 group-title=\"Serie\",Storia Immaginaria S01E01\nhttp://cdn/vod/s1.mkv"

    private val guideVersion = AtomicInteger()

    /** 60 channels × one programme every 10 min over [-1 day, +2 days] ≈ 26k programmes (13 batches of 2,000). */
    private fun guide(): String {
        val v = guideVersion.incrementAndGet()
        val sb = StringBuilder("<tv>\n")
        for (c in 1..channels) {
            var s = nowSec - 86_400 - (nowSec % 600)
            while (s < nowSec + 2 * 86_400) {
                sb.append("<programme start=\"").append(t(s)).append("\" stop=\"").append(t(s + 600))
                    .append("\" channel=\"ch").append(c).append("\"><title>Programma v").append(v).append(' ').append(s)
                    .append("</title></programme>\n")
                s += 600
            }
        }
        return sb.append("</tv>").toString()
    }

    private fun http() = HttpClient(MockEngine { req ->
        if (req.url.host == "epg") respond(guide()) else respond(m3u)
    }) { stePlayerDefaults() }

    @Test fun epgImportNotifiesListenersOnceAndNowNextEmitsOnce() = runBlocking {
        val platform = CapturingPlatform(JvmPlatform(http()))
        val core = StePlayerCore(platform)
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/list.m3u"))
        core.sync.sync(id) // catalog + first EPG import

        val list = core.catalog.channels(id, Categories.ALL).first()
        assertEquals(channels, list.size)
        val now = System.currentTimeMillis()
        val emissions = AtomicInteger()
        val job = launch(Dispatchers.Default) { core.epg.nowNext(id, list, now).collect { emissions.incrementAndGet() } }
        withTimeout(5_000) { while (emissions.get() == 0) delay(10) }
        assertEquals(1, emissions.get())

        val counts = platform.driver.countNotifications("epg_program")
        core.epg.refresh(core.sources.byId(id)!!)
        delay(500) // let pending re-queries settle
        job.cancel()

        val notifications = counts.getValue("epg_program").get()
        val nowNextEmissions = emissions.get() - 1
        println("EPG import (~26k programmes): epg_program notifications=$notifications, nowNext emissions=$nowNextEmissions")
        assertEquals(1, notifications, "one notification per import, not one per batch")
        assertTrue(nowNextEmissions in 1..2, "nowNext emitted $nowNextEmissions times during the import")
        val after = core.epg.nowNext(id, list, now).first()
        assertTrue(after.all { it.now?.title?.startsWith("Programma v2") == true })
    }

    @Test fun catalogSyncNotifiesOncePerSection() = runBlocking {
        val platform = CapturingPlatform(JvmPlatform(http()))
        val core = StePlayerCore(platform)
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/list.m3u"))
        val counts = platform.driver.countNotifications("channel", "movie", "series", "category", "epg_program")
        core.sync.sync(id)
        val snapshot = counts.mapValues { it.value.get() }
        println("Catalog sync notifications: $snapshot")
        snapshot.forEach { (table, n) -> assertTrue(n <= 1, "$table notified $n times") }
    }
}

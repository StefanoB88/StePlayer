package com.steplayer.shared

import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.search.ProgramStatus
import com.steplayer.shared.search.SearchHit
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmartSearchTest {
    private val now = System.currentTimeMillis() / 1000
    private fun t(sec: Long): String = java.text.SimpleDateFormat("yyyyMMddHHmmss Z")
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(sec * 1000))
    private fun prog(ch: String, startOff: Long, durMin: Long, title: String, desc: String? = null) =
        """<programme start="${t(now + startOff)}" stop="${t(now + startOff + durMin * 60)}" channel="$ch"><title>$title</title>${desc?.let { "<desc>$it</desc>" } ?: ""}</programme>"""

    private val m3u = """#EXTM3U x-tvg-url="http://epg/guide.xml"
        #EXTINF:-1 tvg-id="sport1" tvg-chno="201" group-title="Sport",Sport Uno
        http://cdn/sport1.m3u8
        #EXTINF:-1 tvg-id="sport1" group-title="Sport",Sport Uno FHD
        http://cdn/sport1fhd.m3u8
        #EXTINF:-1 tvg-id="reg3" tvg-chno="3" group-title="Italia" catchup="default" catchup-days="7" catchup-source="?utc={utc}",Regionale 3
        http://cdn/reg3.m3u8
        #EXTINF:-1 tvg-id="doc" group-title="Documentari",Canale Documentari
        http://cdn/doc.m3u8
        #EXTINF:-1 tvg-id="napolitv" group-title="Locali",Canale Napoli
        http://cdn/napolitv.m3u8
        #EXTINF:-1 group-title="Film",Il tesoro di Napoli (2019)
        http://cdn/vod/oro.mp4
        #EXTINF:-1 group-title="Film",Napoleone e il generale (2010)
        http://cdn/vod/napoleon.mp4
        #EXTINF:-1 group-title="Film",Roma di notte città
        http://cdn/vod/roma.mp4
        #EXTINF:-1 group-title="Serie",Onde del Sud S01E01
        http://cdn/vod/mf101.mkv""".trimIndent()

    private val guide = "<tv>" + listOf(
        prog("sport1", -1800, 120, "Napoli, regata del lungomare", "Barche a vela in diretta dal golfo."),
        prog("doc", 86_400, 60, "Viaggio in Campania", "Un itinerario tra Pompei e i vicoli napoletani."),
        prog("reg3", -86_400, 30, "Notiziario regionale", "Le notizie da Napoli e provincia."),
        prog("reg3", -172_800, 30, "Notiziario regionale", "Le notizie da Napoli e provincia."),
        prog("doc", -86_400, 60, "Napoli sotterranea", "Senza archivio: non deve comparire."),
        prog("sport1", 3 * 3600, 90, "Torneo serale", "Seconda partita."),
        prog("napolitv", 600, 30, "Speciale Napoli", null),
    ).joinToString("\n") + "</tv>"

    private suspend fun core(): Pair<StePlayerCore, Long> {
        val http = HttpClient(MockEngine { req ->
            if (req.url.host == "epg") respond(guide) else respond(m3u)
        }) { stePlayerDefaults() }
        val core = StePlayerCore(JvmPlatform(http))
        val id = core.sources.add(SourceInput.M3u("Test", "http://pl/list.m3u"))
        core.sync.sync(id)
        return core to id
    }

    @Test fun napoliFindsEverything() = runTest {
        val (core, id) = core()
        val r = core.search.search(id, "Napoli")
        assertNull(r.correctedQuery)

        // Live: the match, on one channel only even though two variants share the EPG id
        assertEquals(listOf("Napoli, regata del lungomare"), r.liveNow.map { it.program.title })
        assertEquals("Sport Uno", r.liveNow.single().channel.name)

        // Upcoming: title match first, then description-only match ("napoletani")
        assertEquals(listOf("Speciale Napoli", "Viaggio in Campania"), r.upcoming.map { it.program.title })

        // Replay: collapsed to the latest airing, only on the channel with catch-up
        val replay = r.replay.single()
        assertEquals("Notiziario regionale", replay.program.title)
        assertEquals(1, replay.otherAirings)
        assertEquals(ProgramStatus.REPLAY, replay.status)
        assertTrue(r.replay.none { it.program.title == "Napoli sotterranea" }, "no archive → not playable → hidden")

        assertEquals(listOf("Canale Napoli"), r.channels.map { it.channel.name })
        // Whole-word match ranks above the stem-only match "Napoleone"
        assertEquals(listOf("Il tesoro di Napoli (2019)", "Napoleone e il generale (2010)"), r.movies.map { it.movie.name })

        // Top hit: live match beats everything
        val top = r.topHits.first()
        assertTrue(top is SearchHit.ProgramHit && top.program.title == "Napoli, regata del lungomare", "top=$top")
    }

    @Test fun multiWordAnyOrder() = runTest {
        val (core, id) = core()
        val r = core.search.search(id, "lungomare napoli")
        assertEquals(listOf("Napoli, regata del lungomare"), r.liveNow.map { it.program.title })
        assertTrue(r.movies.isEmpty())
    }

    @Test fun typoIsCorrected() = runTest {
        val (core, id) = core()
        val r = core.search.search(id, "Nappoli")
        assertEquals("napoli", r.correctedQuery)
        assertEquals("Napoli, regata del lungomare", r.liveNow.single().program.title)
    }

    @Test fun accentsAndChannelNumbers() = runTest {
        val (core, id) = core()
        assertEquals("Roma di notte città", core.search.search(id, "citta").movies.single().movie.name)
        assertEquals("Regionale 3", core.search.search(id, "3").channels.first().channel.name)
        assertEquals("Onde del Sud", core.search.search(id, "onde sud").series.single().series.name)
        assertTrue(core.search.search(id, "zzzzqqq").isEmpty)
    }

    @Test fun recentQueries() = runTest {
        val (core, _) = core()
        core.search.rememberQuery("Napoli")
        core.search.rememberQuery("Roma")
        core.search.rememberQuery("napoli")
        assertEquals(listOf("napoli", "Roma"), core.search.recentQueries.first())
        core.search.clearRecentQueries()
        assertEquals(emptyList(), core.search.recentQueries.first())
    }

    @Test fun deletingSourceDropsIndexes() = runTest {
        val (core, id) = core()
        core.sources.delete(id)
        assertTrue(core.search.search(id, "napoli").isEmpty)
    }
}

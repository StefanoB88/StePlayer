package com.steplayer.shared

import com.steplayer.shared.data.Categories
import com.steplayer.shared.epg.TextChunkDecoder
import com.steplayer.shared.model.ContentType
import com.steplayer.shared.model.SourceInput
import com.steplayer.shared.model.SourceType
import com.steplayer.shared.platform.JvmPlatform
import com.steplayer.shared.platform.stePlayerDefaults
import com.steplayer.shared.xtream.XtreamException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntegrationTest {

    private val now = System.currentTimeMillis() / 1000
    private fun xmltvTime(epochSec: Long): String {
        val f = java.text.SimpleDateFormat("yyyyMMddHHmmss Z").apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return f.format(java.util.Date(epochSec * 1000))
    }

    private fun xtreamServer(password: String = "secret") = HttpClient(MockEngine { req ->
        val url = req.url
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        if (url.encodedPath.endsWith("xmltv.php")) {
            val xml = """<tv><programme start="${xmltvTime(now - 600)}" stop="${xmltvTime(now + 1200)}" channel="ch1.epg"><title>Notiziario</title></programme>
                <programme start="${xmltvTime(now + 1200)}" stop="${xmltvTime(now + 3000)}" channel="ch1.epg"><title>Film</title></programme>
                <programme start="${xmltvTime(now)}" stop="${xmltvTime(now + 100)}" channel="unknown"><title>Skip</title></programme></tv>"""
            return@MockEngine respond(gzip(xml), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/gzip"))
        }
        if (url.parameters["password"] != password) {
            return@MockEngine respond("""{"user_info":{"auth":0}}""", HttpStatusCode.OK, json)
        }
        val body = when (url.parameters["action"]) {
            null -> """{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1999999999","max_connections":"2","allowed_output_formats":["m3u8","ts"]},
                       "server_info":{"timezone":"Europe/Rome","timestamp_now":1705339800,"time_now":"2024-01-15 18:30:00"}}"""
            "get_live_categories" -> """[{"category_id":"1","category_name":"News"},{"category_id":2,"category_name":"Sport"}]"""
            "get_vod_categories" -> """[{"category_id":"10","category_name":"Azione"}]"""
            "get_series_categories" -> "[]"
            "get_live_streams" -> """[{"num":1,"name":"Canale 1","stream_id":101,"epg_channel_id":"ch1.epg","category_id":"1","tv_archive":1,"tv_archive_duration":3},
                                     {"num":2,"name":"Sport HD","stream_id":"102","category_id":"2"}]"""
            "get_vod_streams" -> """[{"name":"Città Nascosta (2020)","stream_id":5001,"rating":"8","category_id":"10","container_extension":"mkv","added":"1700000000"}]"""
            "get_series" -> """{}"""
            else -> "[]"
        }
        respond(body, HttpStatusCode.OK, json)
    }) { stePlayerDefaults() }

    @Test fun xtreamEndToEnd() = runTest {
        val core = StePlayerCore(JvmPlatform(xtreamServer()))

        val bad = assertFailsWith<XtreamException> {
            core.sources.add(SourceInput.Xtream("Test", "srv.example:8080", "u", "wrong"))
        }
        assertEquals(XtreamException.Kind.INVALID_CREDENTIALS, bad.kind)

        // Pasting the "get.php" M3U link must upgrade to Xtream.
        val id = core.sources.add(SourceInput.M3u("Mia lista", "http://srv.example:8080/get.php?username=u&password=secret&type=m3u_plus"))
        val source = core.sources.byId(id)!!
        assertEquals(SourceType.XTREAM, source.type)
        assertEquals("http://srv.example:8080", source.url)
        assertTrue(source.toString().contains("secret").not(), "password must never be in the DB")

        core.sync.sync(id)
        val synced = core.sources.byId(id)!!
        assertEquals(3600, synced.serverTimeOffsetSec)
        assertEquals(listOf("m3u8", "ts"), synced.allowedFormats)

        val cats = core.catalog.categories(id, ContentType.LIVE).first()
        assertEquals(listOf("News", "Sport"), cats.map { it.name })
        val news = core.catalog.channels(id, "1").first()
        assertEquals("Canale 1", news.single().name)
        assertEquals(3, news.single().archiveDays)
        assertEquals(1, core.catalog.movies(id, Categories.ALL).first().size)

        val results = core.catalog.search(id, "citta")
        assertEquals("Città Nascosta (2020)", results.movies.single().name)
        assertEquals(2020, results.movies.single().year)

        val stream = core.streams.live(news.single())
        assertEquals("http://srv.example:8080/live/u/secret/101.ts", stream.url)
        val movie = core.streams.movie(results.movies.single())
        assertTrue(movie.url.endsWith("/movie/u/secret/5001.mkv"))

        // EPG imported from gzip, filtered to known channels
        val epg = core.epg.nowNext(id, news).first().single()
        assertEquals("Notiziario", epg.now?.title)
        assertEquals("Film", epg.next?.title)

        // Favorites survive a resync
        core.library.toggleFavorite(id, ContentType.LIVE, "101")
        core.sync.sync(id)
        assertEquals(listOf("101"), core.catalog.channels(id, Categories.FAVORITES).first().map { it.id })

        core.sources.delete(id)
        assertEquals(0, core.catalog.channels(id, Categories.ALL).first().size)
    }

    @Test fun m3uEndToEnd() = runTest {
        val m3u = """#EXTM3U x-tvg-url="http://epg/x.xml"
            #EXTINF:-1 tvg-id="a" group-title="News",Alpha
            http://cdn/a.m3u8
            #EXTINF:-1 tvg-id="a" group-title="Preferiti del provider",Alpha
            http://cdn/a.m3u8
            #EXTINF:-1 group-title="News",Beta
            http://cdn/a.m3u8
            #EXTINF:-1 group-title="Film",Movie One
            http://cdn/vod/m1.mp4
            #EXTINF:-1 group-title="Serie",Show S01E01
            http://cdn/vod/s1e1.mkv
            #EXTINF:-1 group-title="Serie",Show S01E02
            http://cdn/vod/s1e2.mkv""".trimIndent()
        val http = HttpClient(MockEngine { req ->
            if (req.url.host == "epg") respond("<tv></tv>") else respond(m3u)
        }) { stePlayerDefaults() }
        val core = StePlayerCore(JvmPlatform(http))
        val id = core.sources.add(SourceInput.M3u("Lista", "http://pl/list.m3u?token=abc"))
        assertTrue(core.sources.byId(id)!!.url.contains("token").not(), "tokens must not be stored in the DB")
        core.sync.sync(id)
        assertEquals("http://epg/x.xml", core.sources.byId(id)!!.epgUrl)
        // Same stream URL listed 3 times (two groups, two names): every entry is kept.
        assertEquals(listOf("Alpha", "Alpha", "Beta"), core.catalog.channels(id, Categories.ALL).first().map { it.name })
        assertEquals(1, core.catalog.movies(id, Categories.ALL).first().size)
        val show = core.catalog.series(id, Categories.ALL).first().single()
        assertEquals("Show", show.name)
        val detail = core.catalog.seriesDetail(show)
        assertEquals(listOf(1, 2), detail.episodes.map { it.number })
        assertEquals("http://cdn/vod/s1e2.mkv", core.streams.episode(detail.episodes[1]).url)
    }

    @Test fun m3uUrlWithoutSchemeIsAccepted() = runTest {
        val requested = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            requested += req.url.toString()
            respond("#EXTM3U\n#EXTINF:-1 group-title=\"Prova\",Canale Uno\nhttp://cdn/uno.m3u8\n")
        }) { stePlayerDefaults() }
        val core = StePlayerCore(JvmPlatform(http))
        // Typed like the Xtream server field: no "http://".
        val id = core.sources.add(SourceInput.M3u("Lista", "  server.esempio:8080/lista.m3u "))
        core.sync.sync(id)
        assertEquals("http://server.esempio:8080/lista.m3u", requested.first())
        assertEquals(listOf("Canale Uno"), core.catalog.channels(id, Categories.ALL).first().map { it.name })
    }

    @Test fun gzipAndUtf8Splitting() {
        val text = "àèìòù €𝄞 ".repeat(5000)
        for (input in listOf(gzip(text), text.toByteArray())) {
            val sb = StringBuilder()
            val dec = TextChunkDecoder { sb.append(it) }
            input.toList().chunked(7).forEach { c -> dec.feed(c.toByteArray(), c.size) }
            dec.finish()
            assertEquals(text, sb.toString())
        }
    }

    private fun gzip(s: String): ByteArray = ByteArrayOutputStream().also { bos ->
        GZIPOutputStream(bos).use { it.write(s.toByteArray()) }
    }.toByteArray()
}

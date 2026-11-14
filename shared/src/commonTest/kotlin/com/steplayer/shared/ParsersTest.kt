package com.steplayer.shared

import com.steplayer.shared.data.CatchupTemplates
import com.steplayer.shared.epg.XmltvParser
import com.steplayer.shared.epg.XmltvProgramme
import com.steplayer.shared.m3u.M3uClassifier
import com.steplayer.shared.m3u.M3uEntry
import com.steplayer.shared.m3u.M3uKind
import com.steplayer.shared.m3u.M3uParser
import com.steplayer.shared.util.CivilTime
import com.steplayer.shared.util.normalizeForSearch
import com.steplayer.shared.xtream.XtreamClient
import com.steplayer.shared.xtream.XtreamJson
import com.steplayer.shared.xtream.LiveStreamDto
import com.steplayer.shared.xtream.VodStreamDto
import com.steplayer.shared.xtream.XtreamUrls
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class M3uParserTest {
    private val sample = """
        #EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="http://epg2.example/e.xml"
        #EXTINF:-1 tvg-id="uno.tv" tvg-name="Canale Uno HD" tvg-logo="http://l/uno.png" group-title="Italia, Generalisti" catchup="default" catchup-days="7" catchup-source="?utc={utc}&lutc={lutc}",Canale Uno, HD
        http://srv/live/u/p/1.ts
        #EXTINF:-1 group-title="Film",Film di prova (1999)
        #EXTVLCOPT:http-user-agent=VLC/3.0
        http://srv/movie/u/p/55.mkv
        #EXTINF:-1 group-title="Serie" tvg-logo="x.jpg",Serie di prova S02 E05
        http://srv/series/u/p/900.mp4

        #EXTINF:0,Senza attributi
        #EXTGRP:Radio
        http://radio.example/stream
    """.trimIndent()

    private fun parse(text: String = sample): Pair<M3uParser, List<M3uEntry>> {
        val out = mutableListOf<M3uEntry>()
        val p = M3uParser { out += it }
        text.lineSequence().forEach(p::feed)
        return p to out
    }

    @Test fun parsesHeaderAndEntries() {
        val (p, entries) = parse()
        assertEquals(listOf("http://epg.example/guide.xml.gz", "http://epg2.example/e.xml"), p.header.epgUrls)
        assertEquals(4, entries.size)
        val first = entries[0]
        assertEquals("Canale Uno, HD", first.title)
        assertEquals("uno.tv", first.tvgId)
        assertEquals("Italia, Generalisti", first.group)
        assertEquals(7, first.catchupDays)
        assertEquals("?utc={utc}&lutc={lutc}", first.catchupSource)
        assertEquals("VLC/3.0", entries[1].userAgent)
        assertNull(entries[0].userAgent)
        assertEquals("Radio", entries[3].group)
    }

    @Test fun classifiesEntries() {
        val (_, e) = parse()
        assertEquals(M3uKind.LIVE, M3uClassifier.classify(e[0]))
        assertEquals(M3uKind.MOVIE, M3uClassifier.classify(e[1]))
        assertEquals(M3uKind.SERIES, M3uClassifier.classify(e[2]))
        assertEquals(M3uKind.LIVE, M3uClassifier.classify(e[3]))
        val ref = M3uClassifier.parseEpisode("Serie di prova S02 E05")!!
        assertEquals("Serie di prova", ref.showName); assertEquals(2, ref.season); assertEquals(5, ref.episode)
        assertEquals(3, M3uClassifier.parseEpisode("Altra serie - 1x03")!!.episode)
    }

    @Test fun liveChannelServedAsVideoFileStaysLive() {
        val (_, e) = parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"canale.uno\" group-title=\"Generalisti\",Canale Uno\nhttp://cdn/live/uno.mp4\n" +
                "#EXTINF:-1 tvg-chno=\"7\",Canale Sette\nhttp://cdn/live/sette.mkv\n" +
                "#EXTINF:-1 tvg-id=\"\" group-title=\"Film\",Film di prova (2020)\nhttp://cdn/vod/film.mp4\n" +
                "#EXTINF:-1 tvg-id=\"serie.prova\",Serie di prova S01E02\nhttp://cdn/vod/s1e2.mkv\n" +
                "#EXTINF:-1 tvg-id=\"canale.due\",Film in onda\nhttp://srv/movie/u/p/9.mp4\n",
        )
        assertEquals(listOf(M3uKind.LIVE, M3uKind.LIVE, M3uKind.MOVIE, M3uKind.SERIES, M3uKind.MOVIE), e.map(M3uClassifier::classify))
    }

    @Test fun toleratesBomAndCrlf() {
        val (_, e) = parse("﻿#EXTM3U\r\n#EXTINF:-1,Ch\r\nhttp://a/b.ts\r\n")
        assertEquals(1, e.size); assertEquals("Ch", e[0].title)
    }
}

class XmltvParserTest {
    private val xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="x">
          <channel id="uno.tv"><display-name lang="it">Canale Uno</display-name><icon src="http://i/r.png"/></channel>
          <programme start="20240115183000 +0100" stop="20240115200000 +0100" channel="uno.tv">
            <title lang="it">L&apos;Ora del quiz &amp; Co</title>
            <desc><![CDATA[Quiz <serale>]]></desc>
          </programme>
          <!-- comment <programme> -->
          <programme start="20240115200000 +0100" stop="20240115203000 +0100" channel="uno.tv"><title>Notiziario</title></programme>
        </tv>
    """.trimIndent()

    @Test fun parsesInArbitraryChunks() {
        for (chunk in listOf(1, 7, 64, 100_000)) {
            val programmes = mutableListOf<XmltvProgramme>()
            var channelName: String? = null
            val p = XmltvParser(onChannel = { channelName = it.displayName }) { programmes += it }
            xml.chunked(chunk).forEach(p::feed)
            p.finish()
            assertEquals("Canale Uno", channelName, "chunk=$chunk")
            assertEquals(2, programmes.size, "chunk=$chunk")
            assertEquals("L'Ora del quiz & Co", programmes[0].title)
            assertEquals("Quiz <serale>", programmes[0].description)
            // 18:30 +01:00 == 17:30 UTC
            assertEquals(CivilTime.parseSqlDateTime("2024-01-15 17:30:00"), programmes[0].start)
            assertEquals(30 * 60_000L, programmes[1].stop - programmes[1].start)
        }
    }
}

class TimeTest {
    @Test fun civilRoundTrip() {
        val ts = 1_705_339_800L // 2024-01-15T17:30:00Z
        val dt = CivilTime.fromEpochSeconds(ts)
        assertEquals(2024, dt.year); assertEquals(1, dt.month); assertEquals(15, dt.day); assertEquals(17, dt.hour)
        assertEquals(ts, CivilTime.toEpochSeconds(dt))
        assertEquals(ts * 1000, CivilTime.parseXmltv("20240115173000 +0000"))
        assertEquals(ts * 1000, CivilTime.parseXmltv("20240115123000 -0500"))
    }

    @Test fun serverOffset() {
        // Server in UTC+1 reports local 18:30 while UTC is 17:30 (+ a few seconds of latency)
        assertEquals(3600, XtreamClient.computeServerOffset("2024-01-15 18:30:04", 1_705_339_800L))
        assertEquals(0, XtreamClient.computeServerOffset(null, null))
    }
}

class XtreamParsingTest {
    @Test fun toleratesInconsistentTypes() {
        val json = """[
          {"num":1,"name":"A","stream_id":"10","stream_icon":"","epg_channel_id":null,"category_id":"3","tv_archive":1,"tv_archive_duration":"5"},
          {"num":"2","name":"B","stream_id":11,"stream_icon":null,"category_id":3,"tv_archive":"0","tv_archive_duration":false,"extra":{"x":[1]}}
        ]"""
        val list = XtreamJson.decodeFromString(ListSerializer(LiveStreamDto.serializer()), json)
        assertEquals(2, list.size)
        assertEquals("10", list[0].streamId); assertNull(list[0].streamIcon); assertEquals(5, list[0].tvArchiveDuration)
        assertEquals("11", list[1].streamId); assertEquals(2, list[1].num); assertEquals(false, list[1].tvArchive)
    }

    @Test fun vodRatingVariants() {
        val json = """[{"name":"M","stream_id":1,"rating":"7,5","added":"1700000000","container_extension":"mkv"},{"name":"N","stream_id":2,"rating":""}]"""
        val list = XtreamJson.decodeFromString(ListSerializer(VodStreamDto.serializer()), json)
        assertEquals(7.5, list[0].rating); assertEquals(1_700_000_000L, list[0].added); assertNull(list[1].rating)
    }

    @Test fun urls() {
        assertEquals("http://host:8080", XtreamUrls.normalizeBaseUrl("host:8080/"))
        assertEquals("https://server.example", XtreamUrls.normalizeBaseUrl("https://server.example:443/player_api.php?username=a"))
        val c = XtreamUrls.detectFromM3uUrl("http://server.example:8080/get.php?username=u1&password=p%402&type=m3u_plus&output=ts")
        assertNotNull(c)
        assertEquals("http://server.example:8080", c.baseUrl); assertEquals("u1", c.username); assertEquals("p@2", c.password)
        assertEquals("http://server.example:8080/live/u1/p@2/5.ts", XtreamUrls.live(c, "5", "ts"))
        val ts = XtreamUrls.timeshift(c, "5", 1_705_339_800_000L, 90, serverOffsetSec = 3600)
        assertEquals("http://server.example:8080/timeshift/u1/p@2/90/2024-01-15:18-30/5.ts", ts)
    }

    @Test fun base64Epg() {
        assertEquals("Telegiornale", XtreamClient.decodeBase64("VGVsZWdpb3JuYWxl"))
    }
}

class MiscTest {
    @Test fun searchNormalization() {
        assertEquals("la casa di carta", normalizeForSearch("  La_Casa  di CARTÀ "))
    }

    @Test fun catchupTemplates() {
        val url = CatchupTemplates.build("http://s/ch.m3u8", "append|?utc={utc}&d={duration}", 1_000_000L, 4_600_000L)
        assertEquals("http://s/ch.m3u8?utc=1000&d=3600", url)
        val full = CatchupTemplates.build("http://s/ch", "default|http://arc/{Y}/{m}/{d}/{H}-{M}.ts", 1_705_339_800_000L, 1_705_343_400_000L)
        assertEquals("http://arc/2024/01/15/17-30.ts", full)
        assertTrue(CatchupTemplates.build("http://s/ch.ts", "shift|", 1_000_000L, 2_000_000L)!!.startsWith("http://s/ch.ts?utc=1000&lutc="))
    }
}

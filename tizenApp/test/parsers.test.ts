// Ports of ParsersTest.kt, JsonArraySplitterTest.kt and parts of RobustnessTest/IntegrationTest.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { readTextStream } from '../src/core/epg/byteStreams.ts';
import { XmltvParser, type XmltvProgramme } from '../src/core/epg/xmltvParser.ts';
import { CatchupTemplates } from '../src/core/data/streamResolver.ts';
import { M3uClassifier, M3uParser, type M3uEntry, catchupDays, catchupSource, tvgId } from '../src/core/m3u/m3uParser.ts';
import { normalizeForSearch, stableHash, extractYear, redactUrl } from '../src/core/util/text.ts';
import { fromEpochSeconds, parseSqlDateTime, parseXmltv, toEpochSeconds } from '../src/core/util/time.ts';
import {
  computeServerOffset, decodeBase64, decodeListText, liveStreamDto, vodStreamDto,
} from '../src/core/xtream/client.ts';
import { flexBool, flexDouble, flexInt, flexLong, flexString } from '../src/core/xtream/flex.ts';
import { JsonArraySplitter } from '../src/core/xtream/jsonArraySplitter.ts';
import { XtreamUrls } from '../src/core/xtream/urls.ts';
import { gzip } from './helpers.ts';

describe('M3uParser', () => {
  const sample = [
    '#EXTM3U url-tvg="http://epg.example/guide.xml.gz" x-tvg-url="http://epg2.example/e.xml"',
    '#EXTINF:-1 tvg-id="uno.tv" tvg-name="Canale Uno HD" tvg-logo="http://l/uno.png" group-title="Italia, Generalisti" catchup="default" catchup-days="7" catchup-source="?utc={utc}&lutc={lutc}",Canale Uno, HD',
    'http://srv/live/u/p/1.ts',
    '#EXTINF:-1 group-title="Film",Film di prova (1999)',
    '#EXTVLCOPT:http-user-agent=VLC/3.0',
    'http://srv/movie/u/p/55.mkv',
    '#EXTINF:-1 group-title="Serie" tvg-logo="x.jpg",Serie di prova S02 E05',
    'http://srv/series/u/p/900.mp4',
    '',
    '#EXTINF:0,Senza attributi',
    '#EXTGRP:Radio',
    'http://radio.example/stream',
  ].join('\n');

  function parse(text: string = sample): [M3uParser, M3uEntry[]] {
    const out: M3uEntry[] = [];
    const p = new M3uParser((e) => out.push(e));
    for (const line of text.split('\n')) p.feed(line);
    return [p, out];
  }

  test('parses header and entries', () => {
    const [p, entries] = parse();
    assert.deepEqual(p.header.epgUrls, ['http://epg.example/guide.xml.gz', 'http://epg2.example/e.xml']);
    assert.equal(entries.length, 4);
    const first = entries[0];
    assert.equal(first.title, 'Canale Uno, HD');
    assert.equal(tvgId(first), 'uno.tv');
    assert.equal(first.group, 'Italia, Generalisti');
    assert.equal(catchupDays(first), 7);
    assert.equal(catchupSource(first), '?utc={utc}&lutc={lutc}');
    assert.equal(entries[1].userAgent, 'VLC/3.0');
    assert.equal(entries[0].userAgent, null);
    assert.equal(entries[3].group, 'Radio');
  });

  test('classifies entries', () => {
    const [, e] = parse();
    assert.deepEqual(e.map((x) => M3uClassifier.classify(x)), ['LIVE', 'MOVIE', 'SERIES', 'LIVE']);
    const ref = M3uClassifier.parseEpisode('Serie di prova S02 E05');
    assert.deepEqual(ref, { showName: 'Serie di prova', season: 2, episode: 5 });
    assert.equal(M3uClassifier.parseEpisode('Altra serie - 1x03')?.episode, 3);
    assert.equal(M3uClassifier.parseEpisode('Film di prova (2020)'), null);
  });

  test('live channel served as video file stays live; VOD + episode pattern is a series', () => {
    const [, e] = parse(
      '#EXTM3U\n' +
      '#EXTINF:-1 tvg-id="canale.uno" group-title="Generalisti",Canale Uno\nhttp://cdn.example/live/uno.mp4\n' +
      '#EXTINF:-1 tvg-chno="7",Canale Sette\nhttp://cdn.example/live/sette.mkv\n' +
      '#EXTINF:-1 tvg-id="" group-title="Film",Film di prova (2020)\nhttp://cdn.example/vod/film.mp4\n' +
      '#EXTINF:-1 tvg-id="serie.prova",Serie di prova S01E02\nhttp://cdn.example/vod/s1e2.mkv\n' +
      '#EXTINF:-1 tvg-id="canale.due",Film in onda\nhttp://srv.example/movie/u/p/9.mp4\n' +
      '#EXTINF:-1 catchup-days="3",Canale Replay\nhttp://cdn.example/live/replay.mp4\n' +
      '#EXTINF:-1 catchup-source="?t={utc}",Canale Archivio\nhttp://cdn.example/live/arch.mkv\n' +
      '#EXTINF:-1,Episodio qualsiasi S01E01\nhttp://srv.example/series/u/p/77.ts\n',
    );
    assert.deepEqual(e.map((x) => M3uClassifier.classify(x)), ['LIVE', 'LIVE', 'MOVIE', 'SERIES', 'MOVIE', 'LIVE', 'LIVE', 'SERIES']);
  });

  test('tolerates BOM and CRLF', () => {
    const [, e] = parse('﻿#EXTM3U\r\n#EXTINF:-1,Ch\r\nhttp://a.example/b.ts\r\n');
    assert.equal(e.length, 1);
    assert.equal(e[0].title, 'Ch');
  });

  test('lines without #EXTINF need a scheme; quoted attributes may contain commas and single quotes', () => {
    const [, e] = parse("#EXTM3U\nnot-a-url\nhttp://x.example/a.ts\n#EXTINF:-1 tvg-name='Nome, con virgola' group-title=Gruppo,Titolo\nhttp://x.example/b.ts");
    assert.equal(e.length, 2);
    assert.equal(e[0].title, '');
    assert.equal(e[1].attributes['tvg-name'], 'Nome, con virgola');
    assert.equal(e[1].group, 'Gruppo');
  });
});

describe('XmltvParser', () => {
  const xml = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!DOCTYPE tv SYSTEM "xmltv.dtd">',
    '<tv generator-info-name="x">',
    '  <channel id="uno.tv"><display-name lang="it">Canale Uno</display-name><icon src="http://i.example/r.png"/></channel>',
    '  <programme start="20240115183000 +0100" stop="20240115200000 +0100" channel="uno.tv">',
    '    <title lang="it">L&apos;Ora del quiz &amp; Co</title>',
    '    <desc><![CDATA[Quiz <serale>]]></desc>',
    '  </programme>',
    '  <!-- comment <programme> -->',
    '  <programme start="20240115200000 +0100" stop="20240115203000 +0100" channel="uno.tv"><title>Notiziario</title></programme>',
    '</tv>',
  ].join('\n');

  test('parses in arbitrary chunks', () => {
    for (const chunk of [1, 7, 64, 100_000]) {
      const programmes: XmltvProgramme[] = [];
      let channelName: string | null = null;
      let icon: string | null = null;
      const p = new XmltvParser((x) => programmes.push(x), (c) => { channelName = c.displayName; icon = c.icon; });
      for (let i = 0; i < xml.length; i += chunk) p.feed(xml.substring(i, i + chunk));
      p.finish();
      assert.equal(channelName, 'Canale Uno', `chunk=${chunk}`);
      assert.equal(icon, 'http://i.example/r.png');
      assert.equal(programmes.length, 2, `chunk=${chunk}`);
      assert.equal(programmes[0].title, "L'Ora del quiz & Co");
      assert.equal(programmes[0].description, 'Quiz <serale>');
      // 18:30 +01:00 == 17:30 UTC
      assert.equal(programmes[0].start, parseSqlDateTime('2024-01-15 17:30:00'));
      assert.equal(programmes[1].stop - programmes[1].start, 30 * 60_000);
    }
  });

  test('channel pre-filter skips unwanted programmes; entities and ">" in attributes', () => {
    const doc = '<tv><programme start="20240115183000 +0000" stop="20240115190000 +0000" channel="a&amp;b"><title>&#x41;&#66;&quot;</title></programme>' +
      '<programme start="20240115183000" stop="20240115190000" channel="altro"><title>Saltato</title></programme>' +
      '<programme start="20240115190000" stop="20240115183000" channel="a&amp;b"><title>Fine prima di inizio</title></programme>' +
      '<programme title="x>y" start="20240115190000" stop="20240115200000" channel="a&amp;b"><title>Dopo</title></programme></tv>';
    const out: XmltvProgramme[] = [];
    const p = new XmltvParser((x) => out.push(x), undefined, (id) => id === 'a&b');
    p.feed(doc);
    p.finish();
    assert.deepEqual(out.map((x) => [x.channel, x.title]), [['a&b', 'AB"'], ['a&b', 'Dopo']]);
  });

  test('truncated file keeps complete programmes', () => {
    const out: XmltvProgramme[] = [];
    const p = new XmltvParser((x) => out.push(x));
    p.feed('<tv><programme start="20240115183000" stop="20240115190000" channel="c"><title>Uno</title></programme><programme start="2024011519');
    p.finish();
    assert.equal(out.length, 1);
  });
});

describe('time', () => {
  test('civil round trip', () => {
    const ts = 1_705_339_800; // 2024-01-15T17:30:00Z
    const dt = fromEpochSeconds(ts);
    assert.deepEqual([dt.year, dt.month, dt.day, dt.hour], [2024, 1, 15, 17]);
    assert.equal(toEpochSeconds(dt), ts);
    assert.equal(parseXmltv('20240115173000 +0000'), ts * 1000);
    assert.equal(parseXmltv('20240115123000 -0500'), ts * 1000);
    assert.equal(parseXmltv('202401151730'), ts * 1000);
    assert.equal(parseXmltv('2024'), null);
  });

  test('server offset', () => {
    // Server in UTC+1 reports local 18:30 while UTC is 17:30 (+ a few seconds of latency)
    assert.equal(computeServerOffset('2024-01-15 18:30:04', 1_705_339_800), 3600);
    assert.equal(computeServerOffset('2024-01-15 16:29:50', 1_705_339_800), -3600);
    assert.equal(computeServerOffset(null, null), 0);
  });
});

describe('API Xtream parsing', () => {
  test('tolerates inconsistent types', () => {
    const json = `[
      {"num":1,"name":"A","stream_id":"10","stream_icon":"","epg_channel_id":null,"category_id":"3","tv_archive":1,"tv_archive_duration":"5"},
      {"num":"2","name":"B","stream_id":11,"stream_icon":null,"category_id":3,"tv_archive":"0","tv_archive_duration":false,"extra":{"x":[1]}}
    ]`;
    const list = decodeListText(json, liveStreamDto);
    assert.equal(list.length, 2);
    assert.equal(list[0].streamId, '10'); assert.equal(list[0].streamIcon, null); assert.equal(list[0].tvArchiveDuration, 5);
    assert.equal(list[0].tvArchive, true);
    assert.equal(list[1].streamId, '11'); assert.equal(list[1].num, 2); assert.equal(list[1].tvArchive, false);
    assert.equal(list[1].categoryId, '3');
  });

  test('vod rating variants', () => {
    const list = decodeListText('[{"name":"M","stream_id":1,"rating":"7,5","added":"1700000000","container_extension":"mkv"},{"name":"N","stream_id":2,"rating":""}]', vodStreamDto);
    assert.equal(list[0].rating, 7.5); assert.equal(list[0].added, 1_700_000_000); assert.equal(list[1].rating, null);
  });

  test('flex helpers', () => {
    assert.equal(flexString('null'), null);
    assert.equal(flexString(['x', 'y']), 'x');
    assert.equal(flexString({ a: 1 }), null);
    assert.equal(flexLong('12.9'), 12);
    assert.equal(flexInt('99999999999'), null); // out of range → null, never wrapped
    assert.equal(flexInt('42'), 42);
    assert.equal(flexDouble('3,25'), 3.25);
    assert.equal(flexBool('yes'), true);
    assert.equal(flexBool('0'), false);
    assert.equal(flexBool('2'), true);
    assert.equal(flexBool(null), false);
  });

  test('urls', () => {
    assert.equal(XtreamUrls.normalizeBaseUrl('host:8080/'), 'http://host:8080');
    assert.equal(XtreamUrls.normalizeBaseUrl('https://server.example:443/player_api.php?username=a'), 'https://server.example');
    assert.equal(XtreamUrls.normalizeBaseUrl('http://server.example:80'), 'http://server.example');
    const c = XtreamUrls.detectFromM3uUrl('http://server.example:8080/get.php?username=u1&password=p%402&type=m3u_plus&output=ts');
    assert.ok(c);
    assert.equal(c.baseUrl, 'http://server.example:8080'); assert.equal(c.username, 'u1'); assert.equal(c.password, 'p@2');
    assert.equal(XtreamUrls.live(c, '5', 'ts'), 'http://server.example:8080/live/u1/p@2/5.ts');
    const ts = XtreamUrls.timeshift(c, '5', 1_705_339_800_000, 90, 3600);
    assert.equal(ts, 'http://server.example:8080/timeshift/u1/p@2/90/2024-01-15:18-30/5.ts');
    assert.equal(XtreamUrls.detectFromM3uUrl('http://server.example/lista.m3u?username=a&password=b'), null);
    assert.equal(XtreamUrls.live({ baseUrl: 'http://s.example', username: 'a b/c', password: 'è' }, '1', 'ts'), 'http://s.example/live/a%20b%2Fc/%C3%A8/1.ts');
    assert.equal(XtreamUrls.xmltv({ baseUrl: 'http://s.example', username: 'u', password: 'p@2&x' }), 'http://s.example/xmltv.php?username=u&password=p%402%26x');
  });

  test('base64 guide text', () => {
    assert.equal(decodeBase64('VGVsZWdpb3JuYWxl'), 'Telegiornale');
    assert.equal(decodeBase64('non base64!'), 'non base64!');
    assert.equal(decodeBase64('  '), null);
  });
});

describe('JsonArraySplitter', () => {
  function split(json: string, chunk: number): string[] {
    const out: string[] = [];
    const dec = new TextDecoder();
    const s = new JsonArraySplitter((b, n) => out.push(dec.decode(b.subarray(0, n))));
    const bytes = new TextEncoder().encode(json);
    for (let i = 0; i < bytes.length && !s.isDone; i += chunk) {
      const part = bytes.slice(i, Math.min(bytes.length, i + chunk));
      s.feed(part, part.length);
    }
    s.finish();
    return out;
  }

  test('splits elements across any chunk boundary', () => {
    const json = '﻿ [ {"name":"Città [1], \\"bella\\" {x}","n":1,"tags":[1,[2,3]],"o":{"a":"]"}} ,\n' +
      '{"name":"Ünïcødé 𝄞"}, "scalare", 42, null, [1,2] ]  trailing garbage';
    const expected = [
      '{"name":"Città [1], \\"bella\\" {x}","n":1,"tags":[1,[2,3]],"o":{"a":"]"}}',
      '{"name":"Ünïcødé 𝄞"}', '"scalare"', '42', 'null', '[1,2]',
    ];
    for (const chunk of [1, 2, 3, 7, 64, 100_000]) assert.deepEqual(split(json, chunk), expected, `chunk=${chunk}`);
  });

  test('empty shapes yield nothing', () => {
    for (const body of ['', '   ', '[]', ' [ ] ', '{}', '{"user_info":{}}', 'null']) {
      assert.deepEqual(split(body, 3), [], `body=<${body}>`);
    }
  });

  test('broken bodies fail', () => {
    assert.throws(() => split('[{"a":1},{"b":', 4));
    assert.throws(() => split('<html>Errore</html>', 4));
  });
});

describe('text and catch-up', () => {
  test('search normalization', () => {
    assert.equal(normalizeForSearch('  La_Casa  di CARTÀ '), 'la casa di carta');
    assert.equal(normalizeForSearch('Città-Nascosta: ŽŁ'), 'citta nascosta zł');
  });

  test('stableHash matches Kotlin FNV-1a 64', () => {
    assert.equal(stableHash(''), 'cbf29ce484222325');
    assert.equal(stableHash('a'), 'af63dc4c8601ec8c');
    assert.equal(stableHash('foobar'), '85944171f73967e8');
  });

  test('year and redaction', () => {
    assert.equal(extractYear('Film di prova (1999)'), 1999);
    assert.equal(extractYear('senza anno'), null);
    assert.equal(redactUrl('http://s.example/live/u/p/1.ts?username=a&password=b'), 'http://s.example/live/***/***/1.ts?username=***&password=***');
  });

  test('catch-up templates', () => {
    assert.equal(CatchupTemplates.build('http://s.example/ch.m3u8', 'append|?utc={utc}&d={duration}', 1_000_000, 4_600_000), 'http://s.example/ch.m3u8?utc=1000&d=3600');
    assert.equal(CatchupTemplates.build('http://s.example/ch', 'default|http://arc.example/{Y}/{m}/{d}/{H}-{M}.ts', 1_705_339_800_000, 1_705_343_400_000), 'http://arc.example/2024/01/15/17-30.ts');
    assert.ok(CatchupTemplates.build('http://s.example/ch.ts', 'shift|', 1_000_000, 2_000_000)?.startsWith('http://s.example/ch.ts?utc=1000&lutc='));
    assert.equal(CatchupTemplates.build('http://s.example/c/index.m3u8', 'flussonic|', 1_000_000, 4_600_000), 'http://s.example/c/index-1000-3600.m3u8');
    assert.equal(CatchupTemplates.build('http://s.example/c/mpegts', 'fs|', 1_000_000, 4_600_000), 'http://s.example/c/timeshift_abs-1000.ts');
    assert.equal(CatchupTemplates.build('http://s.example/c', null, 1_000_000, 4_600_000), null);
    assert.equal(CatchupTemplates.build('http://s.example/c', 'default|?b={(b)YmdHMS}&e={(e)YmdHMS}', 1_705_339_800_000, 1_705_343_400_000), 'http://s.example/c?b=20240115173000&e=20240115183000');
  });

  test('time math never divides by zero', () => {
    const start = 1_700_000_000_000;
    assert.equal(CatchupTemplates.fill('?d={duration:0}', start, start + 3_600_000), '?d=3600');
    assert.equal(CatchupTemplates.fill('?d={duration:60}', start, start + 3_600_000), '?d=60');
  });
});

describe('gzip and UTF-8 splitting', () => {
  const text = 'àèìòù €𝄞 '.repeat(5000);
  function reader(bytes: Uint8Array, chunk: number) {
    let off = 0;
    return {
      async read() {
        if (off >= bytes.length) return null;
        const part = bytes.subarray(off, off + chunk);
        off += chunk;
        return part;
      },
      cancel() { off = bytes.length; },
    };
  }

  for (const [label, disableNativeGzip] of [['DecompressionStream', false], ['pako', true]] as const) {
    test(`plain and gzip, 7-byte chunks (${label})`, async () => {
      for (const input of [gzip(text), new TextEncoder().encode(text)]) {
        let out = '';
        await readTextStream(reader(input, 7), (t) => { out += t; }, { disableNativeGzip });
        assert.equal(out, text);
      }
    });
  }

  test('gzip detected by magic bytes, not by name; corrupt gzip fails (pako)', async () => {
    const bad = new Uint8Array([0x1f, 0x8b, 8, 0, 0, 0, 0, 0, 0, 3, 0xff, 0xff, 0xff, 0xff]);
    await assert.rejects(readTextStream(reader(bad, 3), () => undefined, { disableNativeGzip: true }));
  });
});

// Parse-speed measurements on generated large inputs (desktop Node numbers; a 2018 TV's ARM SoC is
// roughly 5-10x slower). Sizes can be scaled with PERF_SCALE=0.1 for a quick run.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { gzipSync } from 'node:zlib';
import { readTextStream } from '../src/core/epg/byteStreams.ts';
import { XmltvParser } from '../src/core/epg/xmltvParser.ts';
import { LineSplitter } from '../src/core/epg/byteStreams.ts';
import { M3uClassifier, M3uParser, displayName } from '../src/core/m3u/m3uParser.ts';
import { CatalogIndexBuilder, EpgIndexBuilder, TextDocs } from '../src/core/search/searchIndex.ts';
import { QueryParser } from '../src/core/search/queryParser.ts';
import { normalizeForSearch, stableHash } from '../src/core/util/text.ts';
import { XtreamClient, vodStreamDto } from '../src/core/xtream/client.ts';
import { fakeHttp, xmltvTime } from './helpers.ts';

const SCALE = Number(process.env.PERF_SCALE ?? '1');
const enc = new TextEncoder();

function mb(bytes: number): string { return (bytes / 1e6).toFixed(1); }
function report(label: string, bytes: number, items: number, ms: number): void {
  console.log(`[perf] ${label}: ${mb(bytes)} MB, ${items} items in ${ms.toFixed(0)} ms → ${(bytes / 1e6 / (ms / 1000)).toFixed(1)} MB/s, ${Math.round(items / (ms / 1000))} items/s`);
}

/** Chunked byte reader over generated text parts (64 KB chunks, like a network stream). */
function readerOf(parts: Uint8Array[]) {
  let i = 0;
  return { async read() { return i < parts.length ? parts[i++] : null; }, cancel() { i = parts.length; } };
}

function chunked(text: string, size = 64 * 1024): Uint8Array[] {
  const bytes = enc.encode(text);
  const out: Uint8Array[] = [];
  for (let i = 0; i < bytes.length; i += size) out.push(bytes.subarray(i, i + size));
  return out;
}

describe('parse speed', () => {
  test('XMLTV: plain and gzip (pako + native), with channel filter', async () => {
    const channels = Math.round(1500 * SCALE);
    const perChannel = 150; // ~10 days of programmes
    const now = Math.floor(Date.now() / 1000);
    const parts: string[] = ['<?xml version="1.0" encoding="UTF-8"?>\n<tv generator-info-name="prova">\n'];
    for (let c = 0; c < channels; c++) parts.push(`<channel id="canale${c}.esempio"><display-name lang="it">Canale ${c}</display-name><icon src="http://img.esempio/${c}.png"/></channel>\n`);
    for (let c = 0; c < channels; c++) {
      for (let p = 0; p < perChannel; p++) {
        const start = now - 7 * 86400 + p * 5400;
        parts.push(`<programme start="${xmltvTime(start)}" stop="${xmltvTime(start + 5400)}" channel="canale${c}.esempio">` +
          `<title lang="it">Programma inventato numero ${p % 40} &amp; amici</title>` +
          `<desc lang="it">Una descrizione di prova per la puntata ${p}: ospiti in studio, rubriche e approfondimenti senza alcun riferimento reale.</desc>` +
          `<category lang="it">Intrattenimento</category></programme>\n`);
      }
    }
    parts.push('</tv>\n');
    const xml = parts.join('');
    const bytes = chunked(xml);
    const size = xml.length;
    const programmes = channels * perChannel;

    // Plain, all channels wanted
    let count = 0;
    let t0 = performance.now();
    const p1 = new XmltvParser(() => { count++; });
    await readTextStream(readerOf(bytes), (t) => p1.feed(t));
    p1.finish();
    let ms = performance.now() - t0;
    assert.equal(count, programmes);
    report('XMLTV plain, every channel', size, count, ms);

    // Plain, 1 channel in 3 present in the list (the common case) + time window + index build
    const wanted = new Set<string>();
    for (let c = 0; c < channels; c += 3) wanted.add(`canale${c}.esempio`);
    const from = Date.now() - 3 * 86400_000;
    const to = Date.now() + 3 * 86400_000;
    let kept = 0;
    const index = new EpgIndexBuilder();
    t0 = performance.now();
    const p2 = new XmltvParser((p) => {
      if (p.stop > from && p.start < to) { kept++; index.add(p.channel, p.start, p.stop, p.title, p.description); }
    }, undefined, (id) => wanted.has(id));
    await readTextStream(readerOf(bytes), (t) => p2.feed(t));
    p2.finish();
    const blob = index.build();
    ms = performance.now() - t0;
    report(`XMLTV plain, 1/3 channels wanted, window ±3 days, ${kept} kept + search index (${blob.withDescriptions ? 'with' : 'without'} descriptions)`, size, kept, ms);

    // Gzip
    const gz = new Uint8Array(gzipSync(Buffer.from(xml)));
    const gzParts: Uint8Array[] = [];
    for (let i = 0; i < gz.length; i += 64 * 1024) gzParts.push(gz.subarray(i, i + 64 * 1024));
    for (const [label, disableNativeGzip] of [['pako', true], ['DecompressionStream', false]] as const) {
      count = 0;
      t0 = performance.now();
      const p3 = new XmltvParser(() => { count++; }, undefined, (id) => wanted.has(id));
      await readTextStream(readerOf(gzParts), (t) => p3.feed(t), { disableNativeGzip });
      p3.finish();
      ms = performance.now() - t0;
      report(`XMLTV gzip (${mb(gz.length)} MB compressed) via ${label}, 1/3 channels wanted`, size, count, ms);
    }
  });

  test('M3U: 200k entries parse + classify + ids + search docs', async () => {
    const n = Math.round(200_000 * SCALE);
    const lines: string[] = ['#EXTM3U x-tvg-url="http://epg.esempio/guida.xml.gz"'];
    for (let i = 0; i < n; i++) {
      const k = i % 4;
      if (k === 0) lines.push(`#EXTINF:-1 tvg-id="canale${i}.esempio" tvg-name="Canale ${i}" tvg-logo="http://img.esempio/${i}.png" group-title="Gruppo ${i % 50}",Canale di prova ${i} HD`, `http://cdn.esempio/live/utente/chiave/${i}.ts`);
      else if (k === 1) lines.push(`#EXTINF:-1 tvg-logo="http://img.esempio/f${i}.jpg" group-title="Film ${i % 30}",Film di prova ${i} (20${10 + (i % 15)})`, `http://cdn.esempio/movie/utente/chiave/${i}.mkv`);
      else if (k === 2) lines.push(`#EXTINF:-1 group-title="Serie ${i % 20}",Serie inventata ${i % 500} S0${1 + (i % 5)}E${10 + (i % 80)}`, `http://cdn.esempio/series/utente/chiave/${i}.mp4`);
      else lines.push(`#EXTINF:-1 group-title="Film",Pellicola senza anno ${i}`, `http://cdn.esempio/vod/${i}.mp4`);
    }
    const text = lines.join('\n') + '\n';
    const bytes = chunked(text);
    const kinds = { LIVE: 0, MOVIE: 0, SERIES: 0 };
    const index = new CatalogIndexBuilder();
    let pos = 0;
    const t0 = performance.now();
    const parser = new M3uParser((e) => {
      const name = displayName(e);
      stableHash(`${e.url}|${name}|${e.group ?? ''}`);
      const kind = M3uClassifier.classify(e);
      kinds[kind]++;
      if (kind === 'SERIES') M3uClassifier.parseEpisode(name);
      index.movie(pos++, normalizeForSearch(name));
    });
    const splitter = new LineSplitter((l) => parser.feed(l));
    await readTextStream(readerOf(bytes), (t) => splitter.feed(t));
    splitter.finish();
    const blob = index.build(0);
    const ms = performance.now() - t0;
    assert.equal(parser.entryCount, n);
    assert.equal(kinds.LIVE + kinds.MOVIE + kinds.SERIES, n);
    report(`M3U (${kinds.LIVE} live / ${kinds.MOVIE} film / ${kinds.SERIES} episodi)`, text.length, n, ms);

    const docs = new TextDocs(blob.text);
    const q0 = performance.now();
    const queries = ['prova 1234', 'serie inventata', 'pellicola', 'canale 99999', 'zzzz'];
    let found = 0;
    for (const q of queries) found += docs.match(QueryParser.meaningfulTokens(q).map(QueryParser.stem), 1500).length;
    const qms = (performance.now() - q0) / queries.length;
    console.log(`[perf] search over ${n} titles (${mb(blob.text.length)} MB index text): ${qms.toFixed(1)} ms per query (${found} hits)`);
  });

  test('API Xtream: 100k VOD items streamed and decoded', async () => {
    const n = Math.round(100_000 * SCALE);
    const item = (i: number) => `{"num":${i},"name":"Pellicola di prova numero ${i} (2020)","stream_type":"movie","stream_id":${i},` +
      `"stream_icon":"http://img.esempio/poster/${i}.jpg","rating":"7.2","rating_5based":3.6,"added":"1700000000",` +
      `"category_id":"${i % 40}","container_extension":"mkv","custom_sid":"","direct_source":"",` +
      `"plot":"Una trama completamente inventata per il titolo numero ${i}, scritta solo per misurare la velocità."}`;
    let total = 0;
    const http = fakeHttp(() => ({
      stream: function* () {
        let buf = '[';
        for (let i = 1; i <= n; i++) {
          buf += (i > 1 ? ',' : '') + item(i);
          if (buf.length > 64 * 1024) { const b = enc.encode(buf); total += b.length; yield b; buf = ''; }
        }
        const b = enc.encode(buf + ']');
        total += b.length;
        yield b;
      },
    }));
    const client = new XtreamClient(http, { baseUrl: 'http://srv.esempio', username: 'utente', password: 'segreto' });
    let count = 0;
    const t0 = performance.now();
    await client.streamList('get_vod_streams', vodStreamDto, (items) => { count += items.length; });
    const ms = performance.now() - t0;
    assert.equal(count, n);
    report('API Xtream get_vod_streams (split + JSON.parse + flex mapping)', total, count, ms);
  });
});

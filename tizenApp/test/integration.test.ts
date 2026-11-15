// Ports of IntegrationTest.kt, SmartSearchTest.kt, RobustnessTest.kt and XtreamStreamingMemoryTest.kt
// (behaviour part) against fake-indexeddb + a fake HTTP server.
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import type { Channel, Core, SyncState } from '../src/core/api.ts';
import { ALL_CATEGORY, FAVORITES_CATEGORY, RECENT_CATEGORY } from '../src/core/api.ts';
import { XtreamException } from '../src/core/index.ts';
import { XtreamClient, vodStreamDto } from '../src/core/xtream/client.ts';
import { fakeHttp, gzip, newCore, xmltvTime } from './helpers.ts';

const nowSec = Math.floor(Date.now() / 1000);

function xtreamServer(password = 'secret') {
  return fakeHttp((url) => {
    if (url.pathname.endsWith('xmltv.php')) {
      const xml = `<tv><programme start="${xmltvTime(nowSec - 600)}" stop="${xmltvTime(nowSec + 1200)}" channel="ch1.epg"><title>Notiziario</title><desc>Le notizie del giorno</desc></programme>
        <programme start="${xmltvTime(nowSec + 1200)}" stop="${xmltvTime(nowSec + 3000)}" channel="ch1.epg"><title>Film di prova</title></programme>
        <programme start="${xmltvTime(nowSec - 2 * 86400)}" stop="${xmltvTime(nowSec - 2 * 86400 + 1800)}" channel="ch1.epg"><title>Replica vecchia</title></programme>
        <programme start="${xmltvTime(nowSec + 5 * 86400)}" stop="${xmltvTime(nowSec + 5 * 86400 + 1800)}" channel="ch1.epg"><title>Troppo avanti</title></programme>
        <programme start="${xmltvTime(nowSec)}" stop="${xmltvTime(nowSec + 100)}" channel="unknown"><title>Skip</title></programme></tv>`;
      return { body: gzip(xml) }; // gzip without any ".gz" in the URL: detected by magic bytes
    }
    if (url.searchParams.get('password') !== password) return { body: '{"user_info":{"auth":0}}' };
    switch (url.searchParams.get('action')) {
      case null:
        return {
          body: `{"user_info":{"username":"u","auth":1,"status":"Active","exp_date":"1999999999","max_connections":"2","active_cons":"0","allowed_output_formats":["m3u8","ts"]},
                  "server_info":{"timezone":"Europe/Rome","timestamp_now":1705339800,"time_now":"2024-01-15 18:30:00"}}`,
        };
      case 'get_live_categories': return { body: '[{"category_id":"1","category_name":"Notizie"},{"category_id":2,"category_name":"Sport"}]' };
      case 'get_vod_categories': return { body: '[{"category_id":"10","category_name":"Azione"}]' };
      case 'get_series_categories': return { body: '[{"category_id":"20","category_name":"Drammi"}]' };
      case 'get_live_streams':
        return {
          body: `[{"num":1,"name":"Canale Uno","stream_id":101,"epg_channel_id":"ch1.epg","category_id":"1","tv_archive":1,"tv_archive_duration":3},
                  {"num":2,"name":"Sport Due","stream_id":"102","category_id":"2"}]`,
        };
      case 'get_vod_streams':
        return {
          body: `[{"name":"Città Nascosta (2020)","stream_id":5001,"rating":"8","category_id":"10","container_extension":"mkv","added":"1700000000"},
                  {"name":"Film di prova","stream_id":5002,"category_id":"10","added":"1600000000"}]`,
        };
      case 'get_series': return { body: '[{"name":"Serie di prova","series_id":7001,"category_id":"20","plot":"Una storia inventata sul lungomare","genre":"Dramma","last_modified":"1700000000"}]' };
      case 'get_vod_info':
        return { body: '{"info":{"plot":"Trama finta","genre":"Azione","cast":"Attore Uno","duration":"01:30:00","backdrop_path":["http://img.example/b.jpg"]},"movie_data":{"container_extension":"mkv"}}' };
      case 'get_series_info':
        return {
          body: `{"info":{"cast":"Attrice Due","backdrop_path":"http://img.example/s.jpg"},"episodes":{"2":[{"id":"9002","episode_num":"1","season":2,"title":"S2E1","container_extension":"mp4"}],
                  "1":[{"id":"9001","episode_num":2,"season":"1","title":"S1E2","info":{"duration_secs":"1500"}},{"id":"9000","episode_num":1,"season":1,"title":"S1E1"}]}}`,
        };
      default: return { body: '[]' };
    }
  });
}

describe('API Xtream end to end', () => {
  test('add, sync, catalog, EPG, streams, favorites, delete', async () => {
    const http = xtreamServer();
    const core = await newCore(http);

    await assert.rejects(core.sources.add({ kind: 'xtream', name: 'Test', serverUrl: 'srv.example:8080', username: 'u', password: 'wrong' }),
      (e: unknown) => e instanceof XtreamException && e.kind === 'INVALID_CREDENTIALS' && e.message === 'Credenziali non valide');

    // Pasting the "get.php" M3U link must upgrade to API Xtream.
    const id = await core.sources.add({ kind: 'm3u', name: 'Lista di prova', listUrl: 'http://srv.example:8080/get.php?username=u&password=secret&type=m3u_plus' });
    const source = await core.sources.byId(id);
    assert.ok(source);
    assert.equal(source.type, 'XTREAM');
    assert.equal(source.url, 'http://srv.example:8080');
    assert.ok(!JSON.stringify(source).includes('secret'), 'password must never be in Source');
    assert.equal((await core.sources.current())?.id, id);
    const input = await core.sources.inputFor(id);
    assert.deepEqual(input, { kind: 'xtream', name: 'Lista di prova', serverUrl: 'http://srv.example:8080', username: 'u', password: 'secret', userAgent: null });

    const states: SyncState[] = [];
    const off = core.sync.onState((s) => states.push(s));
    await core.sync.sync(id);
    off();
    assert.deepEqual(states.map((s) => (s.kind === 'running' ? s.step : s.kind)), ['AUTHENTICATING', 'LIVE', 'MOVIES', 'SERIES', 'EPG', 'done']);
    const synced = await core.sources.byId(id);
    assert.ok(synced);
    assert.equal(synced.serverTimeOffsetSec, 3600);
    assert.deepEqual(synced.allowedFormats, ['m3u8', 'ts']);
    assert.equal(synced.expiresAt, 1_999_999_999_000);
    assert.equal(synced.maxConnections, 2);
    assert.ok(synced.lastSyncAt != null && synced.lastEpgSyncAt != null);

    assert.deepEqual((await core.catalog.categories(id, 'LIVE')).map((c) => c.name), ['Notizie', 'Sport']);
    const news = await core.catalog.channels(id, '1');
    assert.equal(news.length, 1);
    assert.equal(news[0].name, 'Canale Uno');
    assert.equal(news[0].archiveDays, 3);
    assert.equal((await core.catalog.channels(id, ALL_CATEGORY)).length, 2);
    assert.equal((await core.catalog.channelByNumber(id, 2))?.name, 'Sport Due');
    const movies = await core.catalog.movies(id, ALL_CATEGORY);
    assert.deepEqual(movies.map((m) => m.name), ['Città Nascosta (2020)', 'Film di prova']);
    assert.equal(movies[0].year, 2020);
    assert.equal(movies[0].addedAt, 1_700_000_000_000);
    assert.deepEqual((await core.catalog.movies(id, ALL_CATEGORY, 1, 1)).map((m) => m.id), ['5002']);
    assert.deepEqual((await core.catalog.recentMovies(id, 5)).map((m) => m.id), ['5001', '5002']);

    // Hidden categories
    const sport = (await core.catalog.allCategories(id, 'LIVE'))[1];
    await core.catalog.setCategoryHidden(id, 'LIVE', sport, true);
    assert.deepEqual((await core.catalog.categories(id, 'LIVE')).map((c) => c.name), ['Notizie']);
    assert.deepEqual((await core.catalog.allCategories(id, 'LIVE')).map((c) => c.hidden), [false, true]);

    // Details
    const detail = await core.catalog.movieDetail(movies[0]);
    assert.equal(detail.plot, 'Trama finta');
    assert.equal(detail.durationSec, 5400);
    assert.equal(detail.backdrop, 'http://img.example/b.jpg');
    const series = (await core.catalog.series(id, ALL_CATEGORY))[0];
    const sd = await core.catalog.seriesDetail(series);
    assert.deepEqual(sd.seasons, [1, 2]);
    assert.deepEqual(sd.episodes.map((e) => e.id), ['9000', '9001', '9002']);
    assert.equal(sd.episodes[1].durationSec, 1500);
    assert.equal((await core.catalog.episode(id, '9002'))?.title, 'S2E1'); // cached locally

    // Streams: TV uses MPEG-TS for live (like Android); the user agent travels in the headers.
    const live = await core.streams.live(news[0]);
    assert.equal(live.url, 'http://srv.example:8080/live/u/secret/101.ts');
    assert.equal(live.container, 'ts');
    assert.equal(live.headers['User-Agent'], 'StePlayer/1.0 (Tizen)');
    const movie = await core.streams.movie(movies[0]);
    assert.ok(movie.url.endsWith('/movie/u/secret/5001.mkv'));
    const ep = await core.streams.episode(sd.episodes[2]);
    assert.equal(ep.url, 'http://srv.example:8080/series/u/secret/9002.mp4');

    // EPG imported from gzip, filtered to known channels and to the kept window
    const nn = (await core.epg.nowNext(id, news))[0];
    assert.equal(nn.now?.title, 'Notiziario');
    assert.equal(nn.next?.title, 'Film di prova');
    const day = 86_400_000;
    const all = await core.epg.programs(news[0], Date.now() - 7 * day, Date.now() + 7 * day);
    assert.deepEqual(all.map((p) => p.title), ['Replica vecchia', 'Notiziario', 'Film di prova']); // 3 archive days kept, not +5 days
    assert.equal(all[1].description, 'Le notizie del giorno');
    const guide = await core.epg.guide(id, ['ch1.epg', 'ch1.epg', 'unknown'], Date.now() - 3_600_000, Date.now() + 3_600_000);
    assert.deepEqual(Object.keys(guide), ['ch1.epg']);
    assert.equal(guide['ch1.epg'][0].description, null);

    // Catch-up in server time (offset +1h)
    const past = all[0];
    const cu = await core.streams.catchup(news[0], past);
    assert.match(cu.url, /^http:\/\/srv\.example:8080\/timeshift\/u\/secret\/30\/\d{4}-\d{2}-\d{2}:\d{2}-\d{2}\/101\.ts$/);
    await assert.rejects(core.streams.catchup((await core.catalog.channel(id, '102')) as Channel, past), /non supporta il replay/);

    // Search sees catalog and guide
    const r = await core.search.search(id, 'citta');
    assert.deepEqual(r.movies.map((m) => m.movie.name), ['Città Nascosta (2020)']);
    const plot = await core.search.search(id, 'lungomare');
    assert.equal(plot.series[0].matchedInPlot, true);
    assert.equal((await core.search.search(id, 'notiziario')).liveNow[0].program.title, 'Notiziario');

    // Diagnose
    assert.equal(await core.playback.diagnose(id), null);
    assert.equal((await core.playback.errorForHttpStatus(id, 458)).message.includes('2 connessioni'), true);

    // Favorites and progress survive a resync
    let libraryEvents = 0;
    core.subscribe('library', () => libraryEvents++);
    assert.equal(await core.library.toggleFavorite(id, 'LIVE', '101'), true);
    await core.library.saveProgress({ sourceId: id, type: 'MOVIE', itemId: '5001', parentId: null, title: 'Città Nascosta', subtitle: null, image: null, positionMs: 60_000, durationMs: 600_000 });
    await core.library.saveProgress({ sourceId: id, type: 'LIVE', itemId: '102', parentId: null, title: 'Sport Due', subtitle: null, image: null, positionMs: 0, durationMs: 0 });
    assert.equal(libraryEvents, 3);
    await core.sync.sync(id);
    assert.deepEqual((await core.catalog.channels(id, FAVORITES_CATEGORY)).map((c) => c.id), ['101']);
    assert.deepEqual((await core.catalog.channels(id, RECENT_CATEGORY)).map((c) => c.id), ['102']);
    assert.deepEqual((await core.library.continueWatching(id)).map((p) => p.itemId), ['5001']);
    assert.deepEqual([...(await core.library.favoriteIds(id, 'LIVE'))], ['101']);
    assert.deepEqual((await core.catalog.categories(id, 'LIVE')).map((c) => c.name), ['Notizie'], 'hidden category survives a resync');
    assert.equal((await core.catalog.episode(id, '9002')), null, 'episode cache belongs to the old slot');

    await core.sources.delete(id);
    assert.equal((await core.catalog.channels(id, ALL_CATEGORY)).length, 0);
    assert.equal((await core.sources.all()).length, 0);
    assert.equal(await core.sources.inputFor(id), null);
    assert.equal(await core.sources.current(), null);
    assert.equal((await core.search.search(id, 'citta')).movies.length, 0);
  });

  test('expired account and server errors', async () => {
    const expired = fakeHttp(() => ({ body: '{"user_info":{"auth":1,"status":"Expired"}}' }));
    const core = await newCore(expired);
    await assert.rejects(core.sources.add({ kind: 'xtream', name: 'X', serverUrl: 'srv.example', username: 'u', password: 'p' }),
      (e: unknown) => e instanceof XtreamException && e.kind === 'ACCOUNT_EXPIRED' && e.message === "L'abbonamento è scaduto");
    const down = await newCore(fakeHttp(() => ({ status: 500, body: 'no' })));
    await assert.rejects(down.sources.add({ kind: 'xtream', name: 'X', serverUrl: 'srv.example', username: 'u', password: 'p' }), /Errore del server \(500\)/);
    const offline = await newCore(fakeHttp(() => { throw new Error('rete assente'); }));
    await assert.rejects(offline.sources.add({ kind: 'xtream', name: 'X', serverUrl: 'srv.example', username: 'u', password: 'p' }),
      (e: unknown) => e instanceof XtreamException && e.kind === 'NETWORK' && e.message === 'Impossibile contattare il server: rete assente');
  });

  test('a failing sync keeps the previous catalog and reports the error', async () => {
    let broken = false;
    const base = xtreamServer();
    const http = fakeHttp(async (url) => {
      if (broken && url.searchParams.get('action') === 'get_vod_streams') return { body: '[{"name":"a"},{"name":' };
      const res = await base.get(url.toString());
      const parts: Uint8Array[] = [];
      for (let c = await res.read(); c; c = await res.read()) parts.push(c);
      return { body: Buffer.concat(parts) };
    });
    const core = await newCore(http);
    const id = await core.sources.add({ kind: 'xtream', name: 'X', serverUrl: 'srv.example:8080', username: 'u', password: 'secret' });
    await core.sync.sync(id);
    broken = true;
    await assert.rejects(core.sync.sync(id), (e: unknown) => e instanceof XtreamException && e.kind === 'PARSE');
    const st = core.sync.state();
    assert.ok(st.kind === 'failed' && st.message === 'Risposta del server non valida (get_vod_streams)');
    assert.equal((await core.catalog.channels(id, ALL_CATEGORY)).length, 2);
    assert.equal((await core.catalog.movies(id, ALL_CATEGORY)).length, 2);
  });

  test('streamed lists: empty shapes are empty, broken bodies are PARSE errors', async () => {
    const creds = { baseUrl: 'http://srv.example', username: 'utente', password: 'segreto' };
    for (const body of ['{}', 'null', '', '[]']) {
      assert.deepEqual(await new XtreamClient(fakeHttp(() => ({ body })), creds).decodeList('get_vod_streams', vodStreamDto), [], `body=<${body}>`);
    }
    for (const body of ['<html>Manutenzione</html>', '[{"name":"a"},{"name":', '[{"name":"a"}, 12x]', '[1]']) {
      await assert.rejects(new XtreamClient(fakeHttp(() => ({ body })), creds).decodeList('get_vod_streams', vodStreamDto),
        (e: unknown) => e instanceof XtreamException && e.kind === 'PARSE', `body=<${body}>`);
    }
  });
});

describe('M3U end to end', () => {
  test('duplicates kept, series grouped, EPG url from header, tokens never exposed', async () => {
    const m3u = [
      '#EXTM3U x-tvg-url="http://epg.example/x.xml"',
      '#EXTINF:-1 tvg-id="a" group-title="Notizie",Alpha',
      'http://cdn.example/a.m3u8',
      '#EXTINF:-1 tvg-id="a" group-title="Preferiti del provider",Alpha',
      'http://cdn.example/a.m3u8',
      '#EXTINF:-1 group-title="Notizie",Beta',
      'http://cdn.example/a.m3u8',
      '#EXTINF:-1 group-title="Film",Film Uno',
      'http://cdn.example/vod/m1.mp4',
      '#EXTINF:-1 group-title="Serie",Serie di prova S01E01',
      'http://cdn.example/vod/s1e1.mkv',
      '#EXTINF:-1 group-title="Serie",Serie di prova S01E02',
      'http://cdn.example/vod/s1e2.mkv',
      '#EXTINF:-1 tvg-id="r" catchup="append" catchup-days="2" catchup-source="?utc={utc}",Canale Replay',
      'http://cdn.example/r.m3u8',
    ].join('\n');
    const http = fakeHttp((url) => (url.hostname === 'epg.example' ? { body: '<tv></tv>' } : { body: m3u }));
    const core = await newCore(http);
    const id = await core.sources.add({ kind: 'm3u', name: 'Lista', listUrl: 'http://pl.example/list.m3u?token=abc' });
    const src = await core.sources.byId(id);
    assert.equal(src?.url, '', 'the list URL (with tokens) is never in Source');
    assert.ok(!JSON.stringify(await core.sources.all()).includes('token'));
    assert.equal((await core.sources.inputFor(id))?.kind === 'm3u' && (await core.sources.inputFor(id) as { listUrl: string }).listUrl, 'http://pl.example/list.m3u?token=abc');
    await core.sync.sync(id);
    assert.equal((await core.sources.byId(id))?.epgUrl, 'http://epg.example/x.xml');
    // Same stream URL listed 3 times (two groups, two names): every entry is kept.
    const all = await core.catalog.channels(id, ALL_CATEGORY);
    assert.deepEqual(all.map((c) => c.name), ['Alpha', 'Alpha', 'Beta', 'Canale Replay']);
    assert.equal(new Set(all.map((c) => c.id)).size, 4);
    assert.deepEqual((await core.catalog.categories(id, 'LIVE')).map((c) => c.name), ['Notizie', 'Preferiti del provider', 'Senza categoria']);
    assert.equal((await core.catalog.movies(id, ALL_CATEGORY)).length, 1);
    const show = await core.catalog.series(id, ALL_CATEGORY);
    assert.deepEqual(show.map((s) => s.name), ['Serie di prova']);
    const detail = await core.catalog.seriesDetail(show[0]);
    assert.deepEqual(detail.episodes.map((e) => e.number), [1, 2]);
    assert.equal((await core.streams.episode(detail.episodes[1])).url, 'http://cdn.example/vod/s1e2.mkv');
    const movie = (await core.catalog.movies(id, ALL_CATEGORY))[0];
    const ms = await core.streams.movie(movie);
    assert.equal(ms.url, 'http://cdn.example/vod/m1.mp4');
    assert.equal(ms.container, 'mp4');
    const replay = all[3];
    const live = await core.streams.live(replay);
    assert.equal(live.url, 'http://cdn.example/r.m3u8');
    assert.equal(live.container, 'm3u8');
    const cu = await core.streams.catchup(replay, { channelEpgId: 'r', startAt: 1_000_000, endAt: 4_600_000, title: 'x', description: null });
    assert.equal(cu.url, 'http://cdn.example/r.m3u8?utc=1000');
    // ids are stable across syncs (favorites keep working)
    await core.sync.sync(id);
    assert.deepEqual((await core.catalog.channels(id, ALL_CATEGORY)).map((c) => c.id), all.map((c) => c.id));
  });

  test('URL without scheme is accepted; invalid URL is rejected', async () => {
    const http = fakeHttp(() => ({ body: '#EXTM3U\n#EXTINF:-1 group-title="Prova",Canale Uno\nhttp://cdn.example/uno.m3u8\n' }));
    const core = await newCore(http);
    const id = await core.sources.add({ kind: 'm3u', name: 'Lista', listUrl: '  server.esempio:8080/lista.m3u ' });
    await core.sync.sync(id);
    assert.equal(http.requests[0], 'http://server.esempio:8080/lista.m3u');
    assert.deepEqual((await core.catalog.channels(id, ALL_CATEGORY)).map((c) => c.name), ['Canale Uno']);
    await assert.rejects(core.sources.add({ kind: 'm3u', name: 'L', listUrl: 'lista' }), /URL della lista non valido/);
    await core.sources.update(id, { kind: 'm3u', name: 'Rinominata', listUrl: 'altro.esempio/l.m3u', epgUrl: '' });
    assert.equal((await core.sources.byId(id))?.name, 'Rinominata');
    assert.deepEqual(await core.sources.inputFor(id), { kind: 'm3u', name: 'Rinominata', listUrl: 'http://altro.esempio/l.m3u', epgUrl: null, userAgent: null });
  });

  test('HTTP error is reported in Italian', async () => {
    const core = await newCore(fakeHttp(() => ({ status: 404, body: 'x' })));
    const id = await core.sources.add({ kind: 'm3u', name: 'Lista', listUrl: 'http://pl.example/l.m3u' });
    await assert.rejects(core.sync.sync(id), /La lista ha risposto con errore 404/);
    const st = core.sync.state();
    assert.ok(st.kind === 'failed' && st.message === 'La lista ha risposto con errore 404');
  });

  test('gzip M3U and syncIfStale', async () => {
    const http = fakeHttp(() => ({ body: gzip('#EXTM3U\n#EXTINF:-1,Canale Compresso\nhttp://cdn.example/c.ts\n') }));
    const core = await newCore(http);
    const id = await core.sources.add({ kind: 'm3u', name: 'Lista', listUrl: 'http://pl.example/l.m3u' });
    await core.sync.syncIfStale(id);
    assert.equal(http.requests.length, 1);
    await core.sync.syncIfStale(id); // fresh: nothing to do
    assert.equal(http.requests.length, 1);
    assert.deepEqual((await core.catalog.channels(id, ALL_CATEGORY)).map((c) => c.name), ['Canale Compresso']);
  });
});

describe('smart search', () => {
  const t = (sec: number) => xmltvTime(nowSec + sec);
  const prog = (ch: string, startOff: number, durMin: number, title: string, desc?: string) =>
    `<programme start="${t(startOff)}" stop="${t(startOff + durMin * 60)}" channel="${ch}"><title>${title}</title>${desc ? `<desc>${desc}</desc>` : ''}</programme>`;

  const m3u = [
    '#EXTM3U x-tvg-url="http://epg.example/guide.xml"',
    '#EXTINF:-1 tvg-id="sport1" tvg-chno="201" group-title="Sport",Sport Uno',
    'http://cdn.example/sport1.m3u8',
    '#EXTINF:-1 tvg-id="sport1" group-title="Sport",Sport Uno FHD',
    'http://cdn.example/sport1fhd.m3u8',
    '#EXTINF:-1 tvg-id="reg3" tvg-chno="3" group-title="Italia" catchup="default" catchup-days="7" catchup-source="?utc={utc}",Regionale 3',
    'http://cdn.example/reg3.m3u8',
    '#EXTINF:-1 tvg-id="doc" group-title="Documentari",Canale Documentari',
    'http://cdn.example/doc.m3u8',
    '#EXTINF:-1 tvg-id="napolitv" group-title="Locali",Canale Napoli',
    'http://cdn.example/napolitv.m3u8',
    '#EXTINF:-1 group-title="Film",Il tesoro di Napoli (2019)',
    'http://cdn.example/vod/oro.mp4',
    '#EXTINF:-1 group-title="Film",Napoleone e il generale (2010)',
    'http://cdn.example/vod/napoleon.mp4',
    '#EXTINF:-1 group-title="Film",Roma di notte città',
    'http://cdn.example/vod/roma.mp4',
    '#EXTINF:-1 group-title="Serie",Onde del Sud S01E01',
    'http://cdn.example/vod/mf101.mkv',
  ].join('\n');

  const guide = '<tv>' + [
    prog('sport1', -1800, 120, 'Napoli, regata del lungomare', 'Barche a vela in diretta dal golfo.'),
    prog('doc', 86_400, 60, 'Viaggio in Campania', 'Un itinerario tra Pompei e i vicoli napoletani.'),
    prog('reg3', -86_400, 30, 'Notiziario regionale', 'Le notizie da Napoli e provincia.'),
    prog('reg3', -172_800, 30, 'Notiziario regionale', 'Le notizie da Napoli e provincia.'),
    prog('doc', -86_400, 60, 'Napoli sotterranea', 'Senza archivio: non deve comparire.'),
    prog('sport1', 3 * 3600, 90, 'Torneo serale', 'Seconda partita.'),
    prog('napolitv', 600, 30, 'Speciale Napoli'),
  ].join('\n') + '</tv>';

  async function setup(): Promise<[Core, number]> {
    const http = fakeHttp((url) => (url.hostname === 'epg.example' ? { body: guide } : { body: m3u }));
    const core = await newCore(http);
    const id = await core.sources.add({ kind: 'm3u', name: 'Test', listUrl: 'http://pl.example/list.m3u' });
    await core.sync.sync(id);
    return [core, id];
  }

  test('"Napoli" finds everything', async () => {
    const [core, id] = await setup();
    const r = await core.search.search(id, 'Napoli');
    assert.equal(r.correctedQuery, null);
    // Live: on one channel only even though two variants share the guide id
    assert.deepEqual(r.liveNow.map((h) => h.program.title), ['Napoli, regata del lungomare']);
    assert.equal(r.liveNow[0].channel.name, 'Sport Uno');
    // Upcoming: title match first, then description-only match ("napoletani")
    assert.deepEqual(r.upcoming.map((h) => h.program.title), ['Speciale Napoli', 'Viaggio in Campania']);
    // Replay: collapsed to the latest airing, only on the channel with catch-up
    assert.equal(r.replay.length, 1);
    assert.equal(r.replay[0].program.title, 'Notiziario regionale');
    assert.equal(r.replay[0].otherAirings, 1);
    assert.equal(r.replay[0].status, 'REPLAY');
    assert.ok(r.replay.every((h) => h.program.title !== 'Napoli sotterranea'), 'no archive → not playable → hidden');
    assert.deepEqual(r.channels.map((h) => h.channel.name), ['Canale Napoli']);
    // Whole-word match ranks above the stem-only match "Napoleone"
    assert.deepEqual(r.movies.map((h) => h.movie.name), ['Il tesoro di Napoli (2019)', 'Napoleone e il generale (2010)']);
    const top = r.topHits[0];
    assert.ok(top.kind === 'program' && top.program.title === 'Napoli, regata del lungomare');
    assert.deepEqual(r.terms, ['napol']);
  });

  test('multi word any order', async () => {
    const [core, id] = await setup();
    const r = await core.search.search(id, 'lungomare napoli');
    assert.deepEqual(r.liveNow.map((h) => h.program.title), ['Napoli, regata del lungomare']);
    assert.equal(r.movies.length, 0);
  });

  test('typo is corrected', async () => {
    const [core, id] = await setup();
    const r = await core.search.search(id, 'Nappoli');
    assert.equal(r.correctedQuery, 'napoli');
    assert.equal(r.liveNow[0].program.title, 'Napoli, regata del lungomare');
  });

  test('accents and channel numbers', async () => {
    const [core, id] = await setup();
    assert.deepEqual((await core.search.search(id, 'citta')).movies.map((h) => h.movie.name), ['Roma di notte città']);
    assert.equal((await core.search.search(id, '3')).channels[0].channel.name, 'Regionale 3');
    assert.equal((await core.search.search(id, '201')).channels[0].score, 120);
    assert.deepEqual((await core.search.search(id, 'onde sud')).series.map((h) => h.series.name), ['Onde del Sud']);
    const none = await core.search.search(id, 'zzzzqqq');
    assert.equal(none.channels.length + none.movies.length + none.series.length + none.liveNow.length, 0);
    assert.equal((await core.search.search(id, 'a')).topHits.length, 0); // too short
  });

  test('recent queries', async () => {
    const [core] = await setup();
    await core.search.rememberQuery('Napoli');
    await core.search.rememberQuery('Roma');
    await core.search.rememberQuery('napoli');
    await core.search.rememberQuery('x');
    assert.deepEqual(await core.search.recentQueries(), ['napoli', 'Roma']);
    await core.search.clearRecentQueries();
    assert.deepEqual(await core.search.recentQueries(), []);
  });

  test('deleting the source drops the indexes', async () => {
    const [core, id] = await setup();
    await core.sources.delete(id);
    const r = await core.search.search(id, 'napoli');
    assert.equal(r.liveNow.length + r.movies.length + r.channels.length, 0);
  });
});

describe('robustness', () => {
  test('now/next handles tens of thousands of channels', async () => {
    const m3u = '#EXTM3U x-tvg-url="http://epg.example/g.xml"\n#EXTINF:-1 tvg-id="uno",Canale Uno\nhttp://cdn.example/uno.m3u8\n#EXTINF:-1 tvg-id="due",Canale Due\nhttp://cdn.example/due.m3u8\n';
    const xml = `<tv><programme start="${xmltvTime(nowSec - 600)}" stop="${xmltvTime(nowSec + 1200)}" channel="uno"><title>Mattina Inventata</title></programme>
      <programme start="${xmltvTime(nowSec - 600)}" stop="${xmltvTime(nowSec + 1200)}" channel="due"><title>Rubrica Finta</title></programme></tv>`;
    const core = await newCore(fakeHttp((url) => ({ body: url.hostname === 'epg.example' ? xml : m3u, chunk: 4096 })));
    const id = await core.sources.add({ kind: 'm3u', name: 'Lista', listUrl: 'http://pl.example/l.m3u' });
    await core.sync.sync(id);
    const real = await core.catalog.channels(id, ALL_CATEGORY);
    const fake: Channel[] = Array.from({ length: 40_000 }, (_, i) => ({ ...real[0], id: `f${i}`, epgId: `fittizio${i}` }));
    const all = fake.concat(real);
    const result = await core.epg.nowNext(id, all);
    assert.equal(result.length, all.length);
    assert.deepEqual(result.slice(-2).map((r) => r.now?.title), ['Mattina Inventata', 'Rubrica Finta']);
    assert.ok(result.slice(0, -2).every((r) => r.now == null));
  });

  test('settings, reminders and audio delay persist', async () => {
    const core = await newCore(fakeHttp(() => ({ body: '' })));
    assert.equal((await core.playerPreferences.current()).bufferMode, 'LARGE');
    await core.playerPreferences.update((s) => ({ ...s, bufferMode: 'AUTO', preferredAudioLanguages: ['en'] }));
    const s = await core.playerPreferences.current();
    assert.equal(s.bufferMode, 'AUTO', 'explicit choices after the one-time migration are respected');
    assert.deepEqual(s.preferredAudioLanguages, ['en']);
    await core.playerPreferences.setAudioDelayMs('1:LIVE:x', 5000);
    assert.equal(await core.playerPreferences.audioDelayMs('1:LIVE:x'), 3000);
    await core.playerPreferences.setAudioDelayMs('1:LIVE:x', 0);
    assert.equal(await core.playerPreferences.audioDelayMs('1:LIVE:x'), 0);
    assert.equal(await core.reminders.enabled(), true);
    await core.reminders.setEnabled(false);
    assert.equal(await core.reminders.enabled(), false);
    assert.equal(core.tracks.displayName({ id: 'a', language: 'ita', label: null, channelCount: 2, isDefault: false, isForced: false }, 0), 'Italiano · Stereo');
    const z = core.createZapper([], { sourceId: 1, id: 'solo', num: null, name: 'Solo', logo: null, categoryId: null, epgId: null, archiveDays: 0 });
    assert.equal(z.next().id, 'solo');
  });

  test('expiry notice for the active list', async () => {
    const soon = Math.floor(Date.now() / 1000) + 2 * 86400;
    const http = fakeHttp((url) => (url.searchParams.get('action') == null
      ? { body: `{"user_info":{"auth":1,"status":"Active","exp_date":"${soon}"}}` }
      : { body: '[]' }));
    const core = await newCore(http);
    const id = await core.sources.add({ kind: 'xtream', name: 'Lista di prova', serverUrl: 'srv.example', username: 'u', password: 'p' });
    assert.equal(await core.reminders.notice(id), null, 'expiry unknown before the first sync');
    await core.sync.sync(id);
    const n = await core.reminders.notice(id);
    assert.equal(n?.daysLeft, 2);
    assert.equal(n?.message, "L'abbonamento «Lista di prova» scade tra 2 giorni.");
    await core.reminders.setEnabled(false);
    assert.equal(await core.reminders.notice(id), null);
  });
});

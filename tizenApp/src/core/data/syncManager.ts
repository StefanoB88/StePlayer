// Port of data/SyncManager.kt.
//
// Downloads a list's catalog and replaces the local copy atomically. IndexedDB transactions can't
// stay open across network reads, so the new catalog is streamed (batched transactions) into the
// inactive slot and the source flips to it in one final transaction: until then the UI keeps
// reading the old catalog, and a failed sync leaves it untouched. Favorites, progress and hidden
// categories are keyed by item id outside the slots, so they survive a resync.
import type { ContentType, Source, SyncState, SyncStep } from '../api.ts';
import { LineSplitter, readTextStream } from '../epg/byteStreams.ts';
import type { M3uEntry } from '../m3u/m3uParser.ts';
import {
  M3uClassifier, M3uParser, catchupDays, catchupMode, catchupSource, channelNumber, displayName, logoOf, tvgId, tvgName,
} from '../m3u/m3uParser.ts';
import type { HttpResponse } from '../net/http.ts';
import { isSuccess } from '../net/http.ts';
import { NetworkErrors } from '../net/networkErrors.ts';
import { CatalogIndexBuilder } from '../search/searchIndex.ts';
import { logCore, Mutex } from '../util/emitter.ts';
import { extensionOf, extractYear, normalizeForSearch, stableHash } from '../util/text.ts';
import { nowMillis } from '../util/time.ts';
import type { CategoryDto } from '../xtream/client.ts';
import { XtreamClient, XtreamException, liveStreamDto, seriesDto, vodStreamDto } from '../xtream/client.ts';
import type { Ctx } from './context.ts';
import { BatchWriter, CATALOG_STORES, S, prefixRange } from './db.ts';
import type { EpgRepository } from './epgRepository.ts';
import type { CategoryRecord, ChannelRecord, EpisodeRecord, MovieRecord, SeriesRecord } from './records.ts';
import type { SourceRepository } from './sourceRepository.ts';

const DEFAULT_MAX_AGE_MS = 12 * 60 * 60 * 1000;

export class SyncManager {
  private readonly ctx: Ctx;
  private readonly sources: SourceRepository;
  private readonly epg: EpgRepository;
  private readonly mutex = new Mutex();
  private current: SyncState = { kind: 'idle' };
  private readonly listeners: Array<(s: SyncState) => void> = [];

  constructor(ctx: Ctx, sources: SourceRepository, epg: EpgRepository) {
    this.ctx = ctx;
    this.sources = sources;
    this.epg = epg;
  }

  state(): SyncState {
    return this.current;
  }

  onState(listener: (s: SyncState) => void): () => void {
    this.listeners.push(listener);
    return () => {
      const i = this.listeners.indexOf(listener);
      if (i >= 0) this.listeners.splice(i, 1);
    };
  }

  private setState(s: SyncState): void {
    this.current = s;
    for (const l of this.listeners.slice()) {
      try { l(s); } catch (e) { logCore('sync listener failed', e); }
    }
  }

  /** Syncs only if the catalog is older than maxAgeMs (default 12h). Safe to call on every app start. */
  async syncIfStale(sourceId: number, maxAgeMs: number = DEFAULT_MAX_AGE_MS): Promise<void> {
    const source = await this.sources.byId(sourceId);
    if (!source) return;
    const last = source.lastSyncAt ?? 0;
    if (nowMillis() - last > maxAgeMs) {
      await this.sync(sourceId);
    } else if (nowMillis() - (source.lastEpgSyncAt ?? 0) > maxAgeMs) {
      try { await this.epg.refresh(source); } catch (e) { logCore('guide refresh failed', e); }
    }
  }

  /** Full sync. Throws on failure (also reflected in state()). */
  sync(sourceId: number): Promise<void> {
    return this.mutex.withLock(async () => {
      const source = await this.sources.byId(sourceId);
      if (!source) return;
      try {
        if (source.type === 'XTREAM') await this.syncXtream(source);
        else await this.syncM3u(source);
        this.setState({ kind: 'running', step: 'EPG', progress: 0.92 });
        // EPG failures must never fail the catalog sync.
        try {
          await this.epg.refresh((await this.sources.byId(sourceId)) ?? source);
        } catch (e) {
          logCore('guide refresh failed', e);
        }
        this.setState({ kind: 'done' });
      } catch (e) {
        const message = e instanceof Error && e.message ? e.message : 'Sincronizzazione non riuscita';
        this.setState({ kind: 'failed', message });
        throw e;
      }
    });
  }

  /**
   * Progress of one Xtream list step, from `from` to `to`, updated every 500 items. Lists are
   * streamed, so their size isn't known up front: by bytes when the server sends Content-Length,
   * otherwise creeping towards the end.
   */
  private listProgress(step: SyncStep, from: number, to: number): { items(n: number): void; read(bytes: number, total: number | null): void } {
    let count = 0;
    let bytes = 0;
    let size: number | null = null;
    return {
      items: (n: number) => {
        const before = Math.floor(count / 500);
        count += n;
        if (Math.floor(count / 500) === before) return;
        const f = size != null ? Math.min(1, bytes / size) : 1 - 1 / (1 + count / 20_000);
        this.setState({ kind: 'running', step, progress: from + (to - from) * f });
      },
      read: (b: number, total: number | null) => { bytes = b; size = total; },
    };
  }

  // ------------------------------------------------------------------ slots

  private async clearSlot(sourceId: number, g: number): Promise<void> {
    await this.ctx.db.run(CATALOG_STORES, 'readwrite', (tx) => {
      for (const s of CATALOG_STORES) tx.objectStore(s).delete(prefixRange([sourceId, g]));
      return () => undefined;
    });
  }

  /** Atomic switch to the freshly written slot (+ its search index), then frees the old one. */
  private async commit(sourceId: number, g: number, index: CatalogIndexBuilder): Promise<void> {
    const blob = index.build(g);
    let old = -1;
    await this.ctx.db.run([S.sources, S.search], 'readwrite', (tx) => {
      const store = tx.objectStore(S.sources);
      const req = store.get(sourceId);
      req.onsuccess = () => {
        const r = req.result;
        if (!r) return;
        old = r.slot;
        store.put({ ...r, slot: g, lastSyncAt: nowMillis() });
        tx.objectStore(S.search).put(blob, [sourceId, 'catalog']);
      };
      return () => undefined;
    });
    this.sources.forgetSlot(sourceId);
    this.ctx.events.emit('catalog');
    this.ctx.events.emit('sources');
    if (old >= 0 && old !== g) {
      try { await this.clearSlot(sourceId, old); } catch (e) { logCore('old catalog not freed', e); }
    }
  }

  private async withNewSlot(source: Source, write: (g: number) => Promise<CatalogIndexBuilder>): Promise<void> {
    const g = 1 - (await this.sources.slot(source.id));
    await this.clearSlot(source.id, g); // leftovers of an interrupted sync
    let index: CatalogIndexBuilder;
    try {
      index = await write(g);
    } catch (e) {
      try { await this.clearSlot(source.id, g); } catch { /* best effort */ }
      throw e;
    }
    this.setState({ kind: 'running', step: 'SAVING', progress: 0.88 });
    await this.commit(source.id, g, index);
  }

  // ------------------------------------------------------------------ API Xtream

  private async syncXtream(source: Source): Promise<void> {
    const creds = await this.sources.xtreamCredentials(source);
    if (!creds) throw new XtreamException('Username e password mancanti: modifica la lista e inseriscili.', 'INVALID_CREDENTIALS');
    const client = new XtreamClient(this.ctx.http, creds);

    this.setState({ kind: 'running', step: 'AUTHENTICATING', progress: 0.02 });
    const login = await client.login();
    await this.sources.patch(source.id, (r) => ({
      ...r,
      serverTimeOffsetSec: login.serverOffsetSec,
      allowedFormats: login.account.allowedFormats.filter((f) => f.trim() !== ''),
      expiresAt: login.account.expiresAt,
      maxConnections: login.account.maxConnections,
    }));
    this.ctx.events.emit('sources');

    await this.withNewSlot(source, async (g) => {
      const s = source.id;
      const db = this.ctx.db;
      const writer = new BatchWriter(db);
      const index = new CatalogIndexBuilder();
      // Category lists are small: fetched together. Big lists are streamed one at a time.
      const [liveCats, vodCats, seriesCats] = await Promise.all([
        client.liveCategories(), client.vodCategories(), client.seriesCategories(),
      ]);
      const putCategories = (t: ContentType, list: CategoryDto[]): void => {
        list.forEach((c, i) => {
          if (c.categoryId == null) return;
          const rec: CategoryRecord = { s, g, t, id: c.categoryId, name: c.categoryName ?? '—', pos: i };
          writer.add(S.categories, rec);
        });
      };

      this.setState({ kind: 'running', step: 'LIVE', progress: 0.1 });
      putCategories('LIVE', liveCats);
      let pos = 0;
      let progress = this.listProgress('LIVE', 0.1, 0.35);
      await client.streamList('get_live_streams', liveStreamDto, async (items) => {
        progress.items(items.length);
        for (const it of items) {
          const id = it.streamId;
          if (id == null) continue;
          const name = (it.name ?? '').trim() || `Canale ${id}`;
          const rec: ChannelRecord = {
            s, g, id, pos, num: it.num, name, logo: it.streamIcon, cat: it.categoryId, epg: it.epgChannelId,
            arch: it.tvArchive === true ? (it.tvArchiveDuration ?? 0) : 0, url: null, cu: null,
          };
          index.channel(pos, normalizeForSearch(name), it.num);
          pos++;
          writer.add(S.channels, rec);
        }
        await writer.flushIfFull();
      }, progress.read);

      this.setState({ kind: 'running', step: 'MOVIES', progress: 0.35 });
      putCategories('MOVIE', vodCats);
      pos = 0;
      progress = this.listProgress('MOVIES', 0.35, 0.6);
      await client.streamList('get_vod_streams', vodStreamDto, async (items) => {
        progress.items(items.length);
        for (const m of items) {
          const id = m.streamId;
          if (id == null) continue;
          const name = (m.name ?? '').trim() || `Film ${id}`;
          const rec: MovieRecord = {
            s, g, id, pos, name, poster: m.streamIcon, cat: m.categoryId,
            rating: m.rating != null && m.rating > 0 ? m.rating : null,
            year: extractYear(m.year) ?? extractYear(name), ext: m.containerExtension, url: null,
            added: m.added != null ? m.added * 1000 : null,
          };
          index.movie(pos, normalizeForSearch(name));
          pos++;
          writer.add(S.movies, rec);
        }
        await writer.flushIfFull();
      }, progress.read);

      this.setState({ kind: 'running', step: 'SERIES', progress: 0.6 });
      putCategories('SERIES', seriesCats);
      pos = 0;
      progress = this.listProgress('SERIES', 0.6, 0.85);
      await client.streamList('get_series', seriesDto, async (items) => {
        progress.items(items.length);
        for (const it of items) {
          const id = it.seriesId;
          if (id == null) continue;
          const name = (it.name ?? '').trim() || `Serie ${id}`;
          const rec: SeriesRecord = {
            s, g, id, pos, name, cover: it.cover, cat: it.categoryId, plot: it.plot,
            rating: it.rating != null && it.rating > 0 ? it.rating : null,
            year: extractYear(it.year) ?? extractYear(it.releaseDate ?? it.releaseDate2), genre: it.genre,
            modified: it.lastModified != null ? it.lastModified * 1000 : null,
          };
          index.seriesItem(pos, normalizeForSearch(name), it.genre, it.plot);
          pos++;
          writer.add(S.series, rec);
        }
        await writer.flushIfFull();
      }, progress.read);
      await writer.flush();
      return index;
    });
  }

  // ------------------------------------------------------------------ M3U

  private async syncM3u(source: Source): Promise<void> {
    const url = await this.sources.m3uUrl(source);
    if (url == null) throw new Error('URL della lista mancante');
    this.setState({ kind: 'running', step: 'AUTHENTICATING', progress: 0.02 });
    let epgUrls: string[] = [];

    await this.withNewSlot(source, async (g) => {
      let receiving = false;
      try {
        const response = await this.ctx.http.get(url);
        if (!isSuccess(response.status)) {
          response.cancel();
          throw NetworkErrors.fromStatus(response.status, 'LIST');
        }
        receiving = true;
        this.setState({ kind: 'running', step: 'LIVE', progress: 0.1 });
        const total = response.contentLength != null && response.contentLength > 0 ? response.contentLength : null;
        let read = 0;
        let count = 0;
        const counted: HttpResponse = {
          status: response.status,
          async read() {
            const chunk = await response.read();
            if (chunk != null) read += chunk.length;
            return chunk;
          },
          cancel: () => response.cancel(),
        };
        const writer = new BatchWriter(this.ctx.db);
        const sink = new M3uSink(source.id, g, writer);
        const parser = new M3uParser((e) => sink.add(e));
        const lines = new LineSplitter((line) => {
          parser.feed(line);
          if (++count % 2000 === 0) {
            // Size known: real fraction; unknown: creeps towards the end.
            const f = total != null ? Math.min(1, read / total) : 1 - 1 / (1 + count / 40_000);
            this.setState({ kind: 'running', step: 'LIVE', progress: 0.1 + 0.75 * f });
          }
        });
        try {
          await readTextStream(counted, async (text) => {
            lines.feed(text);
            await writer.flushIfFull();
          }, this.ctx.textOptions);
        } finally {
          response.cancel();
        }
        lines.finish();
        await writer.flush();
        epgUrls = parser.header.epgUrls;
        return sink.index;
      } catch (e) {
        if (e instanceof XtreamException) throw e;
        // Network failures (also while reading the list) get a precise message; anything else
        // after the download started (e.g. storage) keeps its own.
        if (!receiving || NetworkErrors.isNetworkFailure(e)) throw NetworkErrors.fromThrowable(e, url, 'LIST');
        throw e;
      }
    });

    if (epgUrls.length > 0) {
      const first = epgUrls[0];
      await this.sources.patch(source.id, (r) => (r.epgUrl == null || r.epgUrl === '' ? { ...r, epgUrl: first } : null));
      this.ctx.events.emit('sources');
    }
  }
}

/** Streams parsed M3U entries into batched writes of the new slot. */
class M3uSink {
  readonly index = new CatalogIndexBuilder();
  private readonly s: number;
  private readonly g: number;
  private readonly writer: BatchWriter;
  private readonly categories = new Map<string, Map<string, number>>(); // type -> (name -> position)
  private readonly seriesIds = new Map<string, string>(); // normalized show -> id
  private readonly usedIds = new Map<string, number>(); // id -> times seen (duplicates in the same list)
  private livePos = 0;
  private moviePos = 0;
  private seriesPos = 0;

  constructor(s: number, g: number, writer: BatchWriter) {
    this.s = s;
    this.g = g;
    this.writer = writer;
  }

  private categoryId(type: ContentType, group: string | null): string {
    const trimmed = group != null ? group.trim() : '';
    const name = trimmed !== '' ? trimmed : 'Senza categoria';
    let map = this.categories.get(type);
    if (!map) { map = new Map(); this.categories.set(type, map); }
    const id = stableHash(name);
    if (!map.has(name)) {
      const position = map.size;
      map.set(name, position);
      const rec: CategoryRecord = { s: this.s, g: this.g, t: type, id, name, pos: position };
      this.writer.add(S.categories, rec);
    }
    return id;
  }

  add(e: M3uEntry): void {
    const s = this.s, g = this.g;
    const display = displayName(e);
    // Stable across syncs, and unique even when the provider lists the same stream URL several
    // times (same channel in more groups, or one URL reused for many entries).
    const base = stableHash(`${e.url}|${display}|${e.group ?? ''}`);
    const seen = this.usedIds.get(base) ?? 0;
    this.usedIds.set(base, seen + 1);
    const id = seen === 0 ? base : `${base}-${seen}`;
    const kind = M3uClassifier.classify(e);
    if (kind === 'LIVE') {
      const num = channelNumber(e);
      const rec: ChannelRecord = {
        s, g, id, pos: this.livePos, num, name: display, logo: logoOf(e), cat: this.categoryId('LIVE', e.group),
        epg: tvgId(e) ?? tvgName(e), arch: catchupDays(e), url: e.url, cu: encodeCatchup(e),
      };
      this.index.channel(this.livePos, normalizeForSearch(display), num);
      this.livePos++;
      this.writer.add(S.channels, rec);
    } else if (kind === 'MOVIE') {
      const rec: MovieRecord = {
        s, g, id, pos: this.moviePos, name: display, poster: logoOf(e), cat: this.categoryId('MOVIE', e.group),
        rating: null, year: extractYear(display), ext: extensionOf(e.url), url: e.url, added: null,
      };
      this.index.movie(this.moviePos, normalizeForSearch(display));
      this.moviePos++;
      this.writer.add(S.movies, rec);
    } else {
      const ref = M3uClassifier.parseEpisode(display);
      const show = ref ? ref.showName : display;
      const key = normalizeForSearch(show);
      let seriesId = this.seriesIds.get(key);
      if (seriesId === undefined) {
        seriesId = stableHash(`series:${key}`);
        const rec: SeriesRecord = {
          s, g, id: seriesId, pos: this.seriesPos, name: show, cover: logoOf(e), cat: this.categoryId('SERIES', e.group),
          plot: null, rating: null, year: extractYear(show), genre: null, modified: null,
        };
        this.index.seriesItem(this.seriesPos, key, null, null);
        this.seriesPos++;
        this.writer.add(S.series, rec);
        this.seriesIds.set(key, seriesId);
      }
      const ep: EpisodeRecord = {
        s, g, id, series: seriesId, season: ref ? ref.season : 1, number: ref ? ref.episode : 0, title: display,
        plot: null, image: logoOf(e), dur: null, ext: extensionOf(e.url), url: e.url,
      };
      this.writer.add(S.episodes, ep);
    }
  }
}

/** Persists catch-up mode and template as "mode|template". */
export function encodeCatchup(e: M3uEntry): string | null {
  const mode = catchupMode(e) ?? (catchupSource(e) != null ? 'default' : null);
  if (mode == null) return null;
  return `${mode}|${catchupSource(e) ?? ''}`;
}

// Entry point of the core (Kotlin StePlayerCore). Create exactly one per page and keep it.
import type { BufferMode, Core, CoreEvent, PlaybackError } from './api.ts';
import { CatalogRepository } from './data/catalogRepository.ts';
import type { Ctx } from './data/context.ts';
import { openDb } from './data/db.ts';
import { EpgRepository } from './data/epgRepository.ts';
import { LibraryRepository } from './data/libraryRepository.ts';
import { SourceRepository } from './data/sourceRepository.ts';
import { StreamResolver } from './data/streamResolver.ts';
import { SyncManager } from './data/syncManager.ts';
import type { Http } from './net/http.ts';
import { createFetchHttp } from './net/http.ts';
import { BufferAdvisor, PlaybackErrors, RetryPolicy, StallWatchdog } from './playback/playbackErrors.ts';
import { Tracks } from './playback/tracks.ts';
import { ExpiryReminders } from './reminders/expiryReminders.ts';
import { SearchIndex } from './search/searchIndex.ts';
import { SmartSearch } from './search/smartSearch.ts';
import { PlayerPreferences } from './settings/playerPreferences.ts';
import { Emitter } from './util/emitter.ts';
import { XtreamClient, XtreamException } from './xtream/client.ts';
import { ChannelZapper } from './zap/channelZapper.ts';

export * from './api.ts';
export type { Http, HttpResponse } from './net/http.ts';
export { HttpError, createFetchHttp } from './net/http.ts';
export { XtreamException } from './xtream/client.ts';
export { StreamException } from './data/streamResolver.ts';
export { contentKey } from './settings/playerPreferences.ts';

export interface CoreOptions {
  /** Device/UI languages in priority order ("it-IT", "en"): fallback for track selection. */
  deviceLanguages?: string[];
  /** HTTP implementation (default: fetch with streaming, timeouts and retries). Inject a fake in tests. */
  http?: Http;
  /** IndexedDB factory (default: the global one; tests pass fake-indexeddb). */
  indexedDB?: IDBFactory;
  /** Database name (default "steplayer"). */
  dbName?: string;
  /** Default user agent for streams (sent by the player via PlayableStream.headers). */
  userAgent?: string;
  /** Forces the pako gzip fallback even where DecompressionStream exists (tests). */
  disableNativeGzip?: boolean;
}

export async function createCore(options: CoreOptions = {}): Promise<Core> {
  const factory = options.indexedDB ?? (globalThis as { indexedDB?: IDBFactory }).indexedDB;
  if (!factory) throw new Error('Archivio locale non disponibile su questo dispositivo');
  const db = await openDb(factory, options.dbName);
  const events = new Emitter<CoreEvent>();
  const ctx: Ctx = {
    db,
    http: options.http ?? createFetchHttp(),
    events,
    defaultUserAgent: options.userAgent ?? 'StePlayer/1.0 (Tizen)',
    prefersHlsLive: false,
    textOptions: { disableNativeGzip: options.disableNativeGzip === true },
  };
  const deviceLanguages = options.deviceLanguages ?? [];

  const sources = new SourceRepository(ctx);
  const library = new LibraryRepository(ctx);
  const catalog = new CatalogRepository(ctx, sources, library);
  const epg = new EpgRepository(ctx, sources);
  const sync = new SyncManager(ctx, sources, epg);
  const searchIndex = new SearchIndex(ctx, sources);
  const search = new SmartSearch(ctx, searchIndex, catalog);
  const streams = new StreamResolver(ctx, sources, catalog);
  const reminders = new ExpiryReminders(ctx, sources);
  const prefs = new PlayerPreferences(ctx);

  /**
   * Asks the API Xtream server what is going on after an ambiguous failure: expired account?
   * all connections busy? Null when nothing more specific is known (or for M3U lists).
   */
  async function diagnose(sourceId: number): Promise<PlaybackError | null> {
    const source = await sources.byId(sourceId);
    if (!source) return null;
    const creds = await sources.xtreamCredentials(source);
    if (!creds) return null;
    try {
      const account = (await new XtreamClient(ctx.http, creds).login()).account;
      const max = account.maxConnections;
      const active = account.activeConnections;
      if (account.status != null && account.status.toLowerCase() !== 'active') return PlaybackErrors.expired();
      if (max != null && active != null && active >= max) return PlaybackErrors.maxConnections(max);
      return null;
    } catch (e) {
      if (e instanceof XtreamException) {
        if (e.kind === 'ACCOUNT_EXPIRED') return PlaybackErrors.expired();
        if (e.kind === 'INVALID_CREDENTIALS') return PlaybackErrors.fromHttpStatus(401);
        if (e.kind === 'NETWORK') return PlaybackErrors.network();
        return null;
      }
      throw e;
    }
  }

  const languages = (given: string[]): string[] => (given.length > 0 ? given : deviceLanguages);

  return {
    subscribe: (event, listener) => events.on(event, listener),

    sources: {
      current: () => sources.current(),
      all: () => sources.all(),
      byId: (id) => sources.byId(id),
      setActive: (id) => sources.setActive(id),
      add: (input) => sources.add(input),
      update: (id, input) => sources.update(id, input),
      delete: (id) => sources.delete(id),
      inputFor: (id) => sources.inputFor(id),
    },

    sync: {
      state: () => sync.state(),
      onState: (listener) => sync.onState(listener),
      sync: (id) => sync.sync(id),
      syncIfStale: (id, maxAgeMs) => sync.syncIfStale(id, maxAgeMs),
    },

    catalog: {
      categories: (s, t) => catalog.categories(s, t),
      allCategories: (s, t) => catalog.allCategories(s, t),
      setCategoryHidden: (s, t, c, h) => catalog.setCategoryHidden(s, t, c, h),
      channels: (s, c) => catalog.channels(s, c),
      channel: (s, id) => catalog.channel(s, id),
      channelByNumber: (s, n) => catalog.channelByNumber(s, n),
      movies: (s, c, limit, offset) => catalog.movies(s, c, limit, offset),
      movie: (s, id) => catalog.movie(s, id),
      movieDetail: (m) => catalog.movieDetail(m),
      series: (s, c, limit, offset) => catalog.series(s, c, limit, offset),
      seriesById: (s, id) => catalog.seriesById(s, id),
      seriesDetail: (se) => catalog.seriesDetail(se),
      episode: (s, id) => catalog.episode(s, id),
      recentMovies: (s, limit) => catalog.recentMovies(s, limit),
    },

    epg: {
      refresh: (source) => epg.refresh(source),
      nowNext: (s, channels, now) => epg.nowNext(s, channels, now),
      programs: (ch, from, to) => epg.programs(ch, from, to),
      guide: (s, ids, from, to) => epg.guide(s, ids, from, to),
    },

    library: {
      isFavorite: (s, t, id) => library.isFavorite(s, t, id),
      favoriteIds: (s, t) => library.favoriteIds(s, t),
      toggleFavorite: (s, t, id) => library.toggleFavorite(s, t, id),
      saveProgress: (p) => library.saveProgress(p),
      progress: (s, t, id) => library.progress(s, t, id),
      seriesProgress: (s, id) => library.seriesProgress(s, id),
      continueWatching: (s, limit) => library.continueWatching(s, limit),
      recentChannels: (s, limit) => library.recentChannels(s, limit),
      removeProgress: (s, t, id) => library.removeProgress(s, t, id),
    },

    search: {
      search: (s, q) => search.search(s, q),
      recentQueries: () => search.recentQueries(),
      rememberQuery: (q) => search.rememberQuery(q),
      clearRecentQueries: () => search.clearRecentQueries(),
    },

    streams: {
      live: (ch) => streams.live(ch),
      catchup: (ch, p) => streams.catchup(ch, p),
      movie: (m) => streams.movie(m),
      episode: (e) => streams.episode(e),
    },

    playback: {
      retryPolicy: (isLive) => new RetryPolicy(isLive),
      stallWatchdog: (isLive) => new StallWatchdog(isLive),
      bufferAdvisor: (mode: BufferMode) => new BufferAdvisor(mode),
      errorForHttpStatus: async (sourceId, status) =>
        PlaybackErrors.fromHttpStatus(status, (await sources.byId(sourceId))?.maxConnections ?? null),
      diagnose,
      errors: {
        network: () => PlaybackErrors.network(),
        timeout: () => PlaybackErrors.timeout(),
        stalled: () => PlaybackErrors.stalled(),
        unsupported: (container) => PlaybackErrors.unsupported(container),
        unknown: (detail) => PlaybackErrors.unknown(detail ?? null),
      },
    },

    reminders: {
      notice: (s, now) => reminders.notice(s, now),
      enabled: () => reminders.enabled(),
      setEnabled: (v) => reminders.setEnabled(v),
    },

    playerPreferences: {
      current: () => prefs.current(),
      update: (transform) => prefs.update(transform),
      audioDelayMs: (key) => prefs.audioDelayMs(key),
      setAudioDelayMs: (key, ms) => prefs.setAudioDelayMs(key, ms),
    },

    tracks: {
      pickAudio: (tracks, settings, langs) => Tracks.pickAudio(tracks, settings, languages(langs)),
      pickSubtitle: (tracks, settings, langs, audioLanguage) => Tracks.pickSubtitle(tracks, settings, languages(langs), audioLanguage),
      displayName: (track, index) => Tracks.displayName(track, index),
    },

    createZapper: (channels, current) => new ChannelZapper(channels, current),
  };
}

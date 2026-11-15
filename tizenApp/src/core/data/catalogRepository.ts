// Port of data/CatalogRepository.kt.
import type { Category, Channel, ContentType, Episode, Movie, MovieDetail, Series, SeriesDetail } from '../api.ts';
import { ALL_CATEGORY, FAVORITES_CATEGORY, RECENT_CATEGORY } from '../api.ts';
import { logCore } from '../util/emitter.ts';
import type { Json } from '../xtream/flex.ts';
import { field, flexInt, flexString } from '../xtream/flex.ts';
import { XtreamClient, XtreamInfoParser } from '../xtream/client.ts';
import type { Ctx } from './context.ts';
import { S, prefixRange } from './db.ts';
import type { LibraryRepository } from './libraryRepository.ts';
import type { CategoryPrefRecord, CategoryRecord, ChannelRecord, EpisodeRecord, MovieRecord, SeriesRecord } from './records.ts';
import { toChannel, toEpisode, toMovie, toSeries } from './records.ts';
import type { SourceRepository } from './sourceRepository.ts';

const DEFAULT_LIMIT = 5000;

export class CatalogRepository {
  private readonly ctx: Ctx;
  private readonly sources: SourceRepository;
  private readonly library: LibraryRepository;

  constructor(ctx: Ctx, sources: SourceRepository, library: LibraryRepository) {
    this.ctx = ctx;
    this.sources = sources;
    this.library = library;
  }

  // ---------------- Categories ----------------

  async allCategories(sourceId: number, type: ContentType): Promise<Category[]> {
    const g = await this.sources.slot(sourceId);
    const [rows, prefs] = await Promise.all([
      this.ctx.db.getAll<CategoryRecord>(S.categories, prefixRange([sourceId, g, type]), undefined, 'pos'),
      this.ctx.db.getAll<CategoryPrefRecord>(S.categoryPrefs, prefixRange([sourceId, type])),
    ]);
    const byId = new Map<string, CategoryPrefRecord>();
    for (const p of prefs) byId.set(p.id, p);
    return rows.map((c) => {
      const p = byId.get(c.id);
      return { id: c.id, name: c.name, locked: p ? p.locked : false, hidden: p ? p.hidden : false };
    });
  }

  async categories(sourceId: number, type: ContentType): Promise<Category[]> {
    return (await this.allCategories(sourceId, type)).filter((c) => !c.hidden).map((c) => ({ ...c, hidden: false }));
  }

  async setCategoryHidden(sourceId: number, type: ContentType, category: Category, hidden: boolean): Promise<void> {
    const rec: CategoryPrefRecord = { s: sourceId, t: type, id: category.id, hidden, locked: category.locked };
    await this.ctx.db.put(S.categoryPrefs, rec);
    this.ctx.events.emit('catalog');
  }

  // ---------------- helpers ----------------

  private async byIds<R, T>(store: 'channels' | 'movies' | 'series', sourceId: number, ids: string[], map: (r: R) => T): Promise<T[]> {
    const g = await this.sources.slot(sourceId);
    const rows = await this.ctx.db.getMany<R>(store, ids.map((id) => [sourceId, g, id]));
    const out: T[] = [];
    for (const r of rows) if (r != null) out.push(map(r));
    return out;
  }

  private async page<R, T>(
    store: 'channels' | 'movies' | 'series', sourceId: number, categoryId: string | null, limit: number, offset: number,
    map: (r: R) => T,
  ): Promise<T[]> {
    const g = await this.sources.slot(sourceId);
    const out: T[] = [];
    if (limit <= 0) return out;
    const range = categoryId == null ? prefixRange([sourceId, g]) : prefixRange([sourceId, g, categoryId]);
    await this.ctx.db.scan<R>(store, range, { index: categoryId == null ? 'pos' : 'cat', offset }, (r) => {
      out.push(map(r));
      return out.length < limit;
    });
    return out;
  }

  private async one<R, T>(store: 'channels' | 'movies' | 'series' | 'episodes', sourceId: number, id: string, map: (r: R) => T): Promise<T | null> {
    const g = await this.sources.slot(sourceId);
    const r = await this.ctx.db.get<R>(store, [sourceId, g, id]);
    return r != null ? map(r) : null;
  }

  // ---------------- Live ----------------

  async channels(sourceId: number, categoryId: string): Promise<Channel[]> {
    if (categoryId === FAVORITES_CATEGORY) {
      const favs = await this.library.favorites(sourceId, 'LIVE');
      return this.byIds<ChannelRecord, Channel>(S.channels, sourceId, favs.map((f) => f.id), toChannel);
    }
    if (categoryId === RECENT_CATEGORY) {
      // Recently watched channels (not in Kotlin, where "__recent__" only applies to movies/series).
      const recent = await this.library.recentChannels(sourceId, 50);
      return this.byIds<ChannelRecord, Channel>(S.channels, sourceId, recent.map((p) => p.itemId), toChannel);
    }
    const g = await this.sources.slot(sourceId);
    if (categoryId === ALL_CATEGORY) {
      const rows = await this.ctx.db.getAll<ChannelRecord>(S.channels, prefixRange([sourceId, g]), undefined, 'pos');
      return rows.map(toChannel);
    }
    const rows = await this.ctx.db.getAll<ChannelRecord>(S.channels, prefixRange([sourceId, g, categoryId]), undefined, 'cat');
    return rows.map(toChannel);
  }

  channel(sourceId: number, id: string): Promise<Channel | null> {
    return this.one<ChannelRecord, Channel>(S.channels, sourceId, id, toChannel);
  }

  /** Raw record (stream URL and catch-up template of M3U channels). */
  async channelRecord(sourceId: number, id: string): Promise<ChannelRecord | null> {
    return this.one<ChannelRecord, ChannelRecord>(S.channels, sourceId, id, (r) => r);
  }

  /** Channel by its provider number (remote-control number entry across the whole list). */
  async channelByNumber(sourceId: number, n: number): Promise<Channel | null> {
    const g = await this.sources.slot(sourceId);
    const rows = await this.ctx.db.getAll<ChannelRecord>(S.channels, prefixRange([sourceId, g, n]), 1, 'num');
    return rows.length > 0 ? toChannel(rows[0]) : null;
  }

  async channelsByNumber(sourceId: number, n: number): Promise<Channel[]> {
    const g = await this.sources.slot(sourceId);
    return (await this.ctx.db.getAll<ChannelRecord>(S.channels, prefixRange([sourceId, g, n]), undefined, 'num')).map(toChannel);
  }

  /** Channels sharing guide ids, in list order (Kotlin channelsByEpgIds). */
  async channelsByEpgIds(sourceId: number, epgIds: string[]): Promise<Map<string, Channel[]>> {
    const g = await this.sources.slot(sourceId);
    const rows = await this.ctx.db.getAllMany<ChannelRecord>(S.channels, epgIds.map((id) => prefixRange([sourceId, g, id])), 'epg');
    const out = new Map<string, Channel[]>();
    epgIds.forEach((id, i) => {
      const list = rows[i].slice().sort((a, b) => a.pos - b.pos);
      if (list.length > 0) out.set(id, list.map(toChannel));
    });
    return out;
  }

  /** Items by list position (search hits). Missing positions are skipped. */
  async byPositions<R>(store: 'channels' | 'movies' | 'series', sourceId: number, g: number, positions: number[]): Promise<R[]> {
    const rows = await this.ctx.db.getMany<R>(store, positions.map((p) => [sourceId, g, p]), 'pos');
    return rows.filter((r): r is R => r != null);
  }

  // ---------------- Movies ----------------

  async movies(sourceId: number, categoryId: string, limit = DEFAULT_LIMIT, offset = 0): Promise<Movie[]> {
    if (categoryId === FAVORITES_CATEGORY) {
      const favs = await this.library.favorites(sourceId, 'MOVIE');
      return this.byIds<MovieRecord, Movie>(S.movies, sourceId, favs.map((f) => f.id), toMovie);
    }
    if (categoryId === RECENT_CATEGORY) return this.recentMovies(sourceId, limit);
    return this.page<MovieRecord, Movie>(S.movies, sourceId, categoryId === ALL_CATEGORY ? null : categoryId, limit, offset, toMovie);
  }

  movie(sourceId: number, id: string): Promise<Movie | null> {
    return this.one<MovieRecord, Movie>(S.movies, sourceId, id, toMovie);
  }

  async movieRecord(sourceId: number, id: string): Promise<MovieRecord | null> {
    return this.one<MovieRecord, MovieRecord>(S.movies, sourceId, id, (r) => r);
  }

  /** ORDER BY added_at DESC (SQLite puts NULLs last: they follow in list order). */
  async recentMovies(sourceId: number, limit: number): Promise<Movie[]> {
    return this.newestFirst<MovieRecord, Movie>(S.movies, 'added', sourceId, limit, (r) => r.added, toMovie);
  }

  private async newestFirst<R extends { pos: number }, T>(
    store: 'movies' | 'series', index: string, sourceId: number, limit: number, key: (r: R) => number | null, map: (r: R) => T,
  ): Promise<T[]> {
    const out: T[] = [];
    if (limit <= 0) return out;
    const g = await this.sources.slot(sourceId);
    await this.ctx.db.scan<R>(store, prefixRange([sourceId, g]), { index, direction: 'prev' }, (r) => {
      out.push(map(r));
      return out.length < limit;
    });
    if (out.length < limit) {
      await this.ctx.db.scan<R>(store, prefixRange([sourceId, g]), { index: 'pos' }, (r) => {
        if (key(r) == null) out.push(map(r));
        return out.length < limit;
      });
    }
    return out;
  }

  /** Rich details (plot, cast, backdrop...). Network call for API Xtream; local-only for M3U. */
  async movieDetail(movie: Movie): Promise<MovieDetail> {
    const basic: MovieDetail = {
      movie, plot: null, genre: null, cast: null, director: null, releaseDate: null, durationSec: null, backdrop: null,
      trailerYoutubeId: null,
    };
    const source = await this.sources.byId(movie.sourceId);
    if (!source) return basic;
    const creds = await this.sources.xtreamCredentials(source);
    if (!creds) return basic;
    let root: Json;
    try {
      root = await new XtreamClient(this.ctx.http, creds).vodInfo(movie.id);
    } catch (e) {
      logCore('movie details unavailable', e);
      return basic;
    }
    if (root == null) return basic;
    const info = field(root, 'info');
    return {
      movie: {
        ...movie,
        poster: movie.poster ?? flexString(field(info, 'movie_image')),
        extension: movie.extension ?? flexString(field(field(root, 'movie_data'), 'container_extension')),
      },
      plot: flexString(field(info, 'plot')) ?? flexString(field(info, 'description')),
      genre: flexString(field(info, 'genre')),
      cast: flexString(field(info, 'cast')) ?? flexString(field(info, 'actors')),
      director: flexString(field(info, 'director')),
      releaseDate: flexString(field(info, 'releasedate')) ?? flexString(field(info, 'release_date')),
      durationSec: XtreamInfoParser.durationSec(info),
      backdrop: XtreamInfoParser.backdrop(info),
      trailerYoutubeId: flexString(field(info, 'youtube_trailer')),
    };
  }

  // ---------------- Series ----------------

  async series(sourceId: number, categoryId: string, limit = DEFAULT_LIMIT, offset = 0): Promise<Series[]> {
    if (categoryId === FAVORITES_CATEGORY) {
      const favs = await this.library.favorites(sourceId, 'SERIES');
      return this.byIds<SeriesRecord, Series>(S.series, sourceId, favs.map((f) => f.id), toSeries);
    }
    if (categoryId === RECENT_CATEGORY) {
      return this.newestFirst<SeriesRecord, Series>(S.series, 'modified', sourceId, limit, (r) => r.modified, toSeries);
    }
    return this.page<SeriesRecord, Series>(S.series, sourceId, categoryId === ALL_CATEGORY ? null : categoryId, limit, offset, toSeries);
  }

  seriesById(sourceId: number, id: string): Promise<Series | null> {
    return this.one<SeriesRecord, Series>(S.series, sourceId, id, toSeries);
  }

  private async cachedEpisodes(sourceId: number, g: number, seriesId: string): Promise<Episode[]> {
    const rows = await this.ctx.db.getAll<EpisodeRecord>(S.episodes, prefixRange([sourceId, g, seriesId]), undefined, 'series');
    return rows.map(toEpisode);
  }

  /**
   * Seasons and episodes. For API Xtream it fetches get_series_info and caches episodes locally,
   * so the detail page opens instantly next time.
   */
  async seriesDetail(series: Series): Promise<SeriesDetail> {
    const g = await this.sources.slot(series.sourceId);
    const cached = await this.cachedEpisodes(series.sourceId, g, series.id);
    const fromCache = (): SeriesDetail => ({ series, backdrop: null, cast: null, director: null, seasons: seasonsOf(cached), episodes: cached });
    const source = await this.sources.byId(series.sourceId);
    const creds = source ? await this.sources.xtreamCredentials(source) : null;
    if (!creds) return fromCache();

    let root: Json;
    try {
      root = await new XtreamClient(this.ctx.http, creds).seriesInfo(series.id);
    } catch (e) {
      logCore('series details unavailable', e);
      return fromCache();
    }
    if (root == null) return fromCache();

    const info = field(root, 'info');
    const episodes: Episode[] = [];
    for (const e of XtreamInfoParser.episodes(root)) {
      const id = flexString(field(e, 'id'));
      if (id == null) continue;
      const epInfo = field(e, 'info');
      episodes.push({
        sourceId: series.sourceId,
        seriesId: series.id,
        id,
        season: flexInt(field(e, 'season')) ?? 1,
        number: flexInt(field(e, 'episode_num')) ?? 0,
        title: flexString(field(e, 'title')) ?? 'Episodio',
        plot: flexString(field(epInfo, 'plot')),
        image: flexString(field(epInfo, 'movie_image')) ?? flexString(field(epInfo, 'cover_big')),
        durationSec: XtreamInfoParser.durationSec(epInfo),
        extension: flexString(field(e, 'container_extension')),
      });
    }
    const sorted = episodes
      .map((ep, i) => ({ ep, i }))
      .sort((a, b) => a.ep.season - b.ep.season || a.ep.number - b.ep.number || a.i - b.i)
      .map((x) => x.ep);

    const s = series.sourceId;
    await this.ctx.db.run(S.episodes, 'readwrite', (tx) => {
      const store = tx.objectStore(S.episodes);
      const req = store.index('series').openKeyCursor(prefixRange([s, g, series.id]));
      req.onsuccess = () => {
        const cursor = req.result;
        if (cursor) {
          store.delete(cursor.primaryKey);
          cursor.continue();
          return;
        }
        for (const ep of sorted) {
          const rec: EpisodeRecord = {
            s, g, id: ep.id, series: ep.seriesId, season: ep.season, number: ep.number, title: ep.title, plot: ep.plot,
            image: ep.image, dur: ep.durationSec, ext: ep.extension, url: null,
          };
          store.put(rec);
        }
      };
      return () => undefined;
    });

    return {
      series: {
        ...series,
        plot: series.plot ?? flexString(field(info, 'plot')),
        cover: series.cover ?? flexString(field(info, 'cover')),
        genre: series.genre ?? flexString(field(info, 'genre')),
      },
      backdrop: XtreamInfoParser.backdrop(info),
      cast: flexString(field(info, 'cast')),
      director: flexString(field(info, 'director')),
      seasons: seasonsOf(sorted),
      episodes: sorted,
    };
  }

  episode(sourceId: number, id: string): Promise<Episode | null> {
    return this.one<EpisodeRecord, Episode>(S.episodes, sourceId, id, toEpisode);
  }

  async episodeRecord(sourceId: number, id: string): Promise<EpisodeRecord | null> {
    return this.one<EpisodeRecord, EpisodeRecord>(S.episodes, sourceId, id, (r) => r);
  }
}

function seasonsOf(episodes: Episode[]): number[] {
  const set: number[] = [];
  for (const e of episodes) if (set.indexOf(e.season) < 0) set.push(e.season);
  return set.sort((a, b) => a - b);
}

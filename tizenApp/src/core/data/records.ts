// Stored record shapes (Kotlin Schema.sq tables) and mappers to the API models (Mappers.kt).
import type { EpgItem } from './epgBuckets.ts';
import type { Channel, ContentType, EpgProgram, Episode, Movie, Series, Source, SourceType, WatchProgress } from '../api.ts';

export interface SourceRecord {
  id?: number;
  name: string;
  type: SourceType;
  /** API Xtream base URL; empty for M3U (the list URL lives with the secrets). */
  url: string;
  username: string | null;
  epgUrl: string | null;
  userAgent: string | null;
  serverTimeOffsetSec: number;
  allowedFormats: string[];
  expiresAt: number | null;
  maxConnections: number | null;
  lastSyncAt: number | null;
  lastEpgSyncAt: number | null;
  createdAt: number;
  position: number;
  /** Active catalog slot (0/1). */
  slot: number;
  /** Longest programme seen in the guide (bounds the "programmes started before" key range). */
  epgMaxDurMs?: number;
}

/** s = source id, g = catalog slot. */
export interface ChannelRecord {
  s: number; g: number; id: string; pos: number; num: number | null; name: string; logo: string | null;
  cat: string | null; epg: string | null; arch: number; url: string | null; cu: string | null;
}
export interface MovieRecord {
  s: number; g: number; id: string; pos: number; name: string; poster: string | null; cat: string | null;
  rating: number | null; year: number | null; ext: string | null; url: string | null; added: number | null;
}
export interface SeriesRecord {
  s: number; g: number; id: string; pos: number; name: string; cover: string | null; cat: string | null;
  plot: string | null; rating: number | null; year: number | null; genre: string | null; modified: number | null;
}
export interface EpisodeRecord {
  s: number; g: number; id: string; series: string; season: number; number: number; title: string;
  plot: string | null; image: string | null; dur: number | null; ext: string | null; url: string | null;
}
export interface CategoryRecord { s: number; g: number; t: ContentType; id: string; name: string; pos: number }
export interface CategoryPrefRecord { s: number; t: ContentType; id: string; hidden: boolean; locked: boolean }
export interface FavoriteRecord { s: number; t: ContentType; id: string; createdAt: number }
export interface ProgressRecord {
  s: number; t: ContentType; id: string; parentId: string | null; title: string; subtitle: string | null;
  image: string | null; positionMs: number; durationMs: number; updatedAt: number;
}

export function toSource(r: SourceRecord): Source {
  return {
    id: r.id as number,
    name: r.name,
    type: r.type,
    url: r.type === 'M3U' ? '' : r.url,
    username: r.username,
    epgUrl: r.epgUrl,
    userAgent: r.userAgent,
    serverTimeOffsetSec: r.serverTimeOffsetSec,
    allowedFormats: r.allowedFormats.slice(),
    expiresAt: r.expiresAt,
    maxConnections: r.maxConnections,
    lastSyncAt: r.lastSyncAt,
    lastEpgSyncAt: r.lastEpgSyncAt,
  };
}

export function toChannel(r: ChannelRecord): Channel {
  return { sourceId: r.s, id: r.id, num: r.num, name: r.name, logo: r.logo, categoryId: r.cat, epgId: r.epg, archiveDays: r.arch };
}

export function toMovie(r: MovieRecord): Movie {
  return {
    sourceId: r.s, id: r.id, name: r.name, poster: r.poster, categoryId: r.cat, rating: r.rating, year: r.year,
    extension: r.ext, addedAt: r.added,
  };
}

export function toSeries(r: SeriesRecord): Series {
  return {
    sourceId: r.s, id: r.id, name: r.name, cover: r.cover, categoryId: r.cat, plot: r.plot, rating: r.rating,
    year: r.year, genre: r.genre,
  };
}

export function toEpisode(r: EpisodeRecord): Episode {
  return {
    sourceId: r.s, seriesId: r.series, id: r.id, season: r.season, number: r.number, title: r.title, plot: r.plot,
    image: r.image, durationSec: r.dur, extension: r.ext,
  };
}

export function toProgram(channelEpgId: string, r: EpgItem, withDescription = true): EpgProgram {
  return { channelEpgId, startAt: r.start, endAt: r.end, title: r.title, description: withDescription ? r.desc : null };
}

export function toProgress(r: ProgressRecord): WatchProgress {
  return {
    sourceId: r.s, type: r.t, itemId: r.id, parentId: r.parentId, title: r.title, subtitle: r.subtitle, image: r.image,
    positionMs: r.positionMs, durationMs: r.durationMs, updatedAt: r.updatedAt,
  };
}

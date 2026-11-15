// Port of data/StreamResolver.kt (downloads are out of scope on TV).
import type { Channel, EpgProgram, Episode, Movie, PlayableStream, Source } from '../api.ts';
import { clamp, extensionOf, pad2, substringAfter, substringBefore } from '../util/text.ts';
import { fromEpochSeconds, nowMillis } from '../util/time.ts';
import { XtreamUrls } from '../xtream/urls.ts';
import type { CatalogRepository } from './catalogRepository.ts';
import type { Ctx } from './context.ts';
import type { SourceRepository } from './sourceRepository.ts';

export class StreamException extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'StreamException';
  }
}

/** Upper bound for a catch-up request (7 days): protects the URL math from garbage EPG times. */
const MAX_CATCHUP_MINUTES = 7 * 24 * 60;

/** Builds playable URLs at the last moment, so passwords never need to be persisted in URLs. */
export class StreamResolver {
  private readonly ctx: Ctx;
  private readonly sources: SourceRepository;
  private readonly catalog: CatalogRepository;

  constructor(ctx: Ctx, sources: SourceRepository, catalog: CatalogRepository) {
    this.ctx = ctx;
    this.sources = sources;
    this.catalog = catalog;
  }

  private async source(id: number): Promise<Source> {
    const s = await this.sources.byId(id);
    if (!s) throw new StreamException('Lista non trovata');
    return s;
  }

  private headers(source: Source): Record<string, string> {
    return { 'User-Agent': source.userAgent ?? this.ctx.defaultUserAgent };
  }

  private async creds(source: Source) {
    const c = await this.sources.xtreamCredentials(source);
    if (!c) throw new StreamException('Credenziali mancanti');
    return c;
  }

  async live(channel: Channel): Promise<PlayableStream> {
    const source = await this.source(channel.sourceId);
    if (source.type === 'XTREAM') {
      const creds = await this.creds(source);
      const formats = source.allowedFormats;
      const hls = this.ctx.prefersHlsLive;
      let ext: string;
      if (hls && (formats.length === 0 || formats.indexOf('m3u8') >= 0)) ext = 'm3u8';
      else if (!hls && (formats.length === 0 || formats.indexOf('ts') >= 0)) ext = 'ts';
      else if (formats.indexOf('m3u8') >= 0) ext = 'm3u8';
      else ext = 'ts';
      return { url: XtreamUrls.live(creds, channel.id, ext), headers: this.headers(source), isLive: true, container: ext };
    }
    const row = await this.catalog.channelRecord(channel.sourceId, channel.id);
    if (!row || row.url == null) throw new StreamException('Canale non trovato');
    return { url: row.url, headers: this.headers(source), isLive: true, container: extensionOf(row.url) };
  }

  /** Catch-up playback of a past programme. */
  async catchup(channel: Channel, program: EpgProgram): Promise<PlayableStream> {
    if (channel.archiveDays <= 0) throw new StreamException('Questo canale non supporta il replay');
    const source = await this.source(channel.sourceId);
    const durationMin = clamp(Math.trunc((program.endAt - program.startAt) / 60_000), 1, MAX_CATCHUP_MINUTES);
    if (source.type === 'XTREAM') {
      const creds = await this.creds(source);
      const url = XtreamUrls.timeshift(creds, channel.id, program.startAt, durationMin, source.serverTimeOffsetSec);
      return { url, headers: this.headers(source), isLive: false, container: 'ts' };
    }
    const row = await this.catalog.channelRecord(channel.sourceId, channel.id);
    if (!row || row.url == null) throw new StreamException('Canale non trovato');
    const url = CatchupTemplates.build(row.url, row.cu, program.startAt, program.endAt);
    if (url == null) throw new StreamException('Replay non disponibile per questo canale');
    return { url, headers: this.headers(source), isLive: false, container: extensionOf(url) };
  }

  async movie(movie: Movie): Promise<PlayableStream> {
    const source = await this.source(movie.sourceId);
    let url: string;
    if (source.type === 'XTREAM') {
      url = XtreamUrls.movie(await this.creds(source), movie.id, movie.extension);
    } else {
      const row = await this.catalog.movieRecord(movie.sourceId, movie.id);
      if (!row || row.url == null) throw new StreamException('Film non trovato');
      url = row.url;
    }
    return { url, headers: this.headers(source), isLive: false, container: movie.extension ?? extensionOf(url) };
  }

  async episode(episode: Episode): Promise<PlayableStream> {
    const source = await this.source(episode.sourceId);
    let url: string;
    if (source.type === 'XTREAM') {
      url = XtreamUrls.episode(await this.creds(source), episode.id, episode.extension);
    } else {
      const row = await this.catalog.episodeRecord(episode.sourceId, episode.id);
      if (!row || row.url == null) throw new StreamException('Episodio non trovato');
      url = row.url;
    }
    return { url, headers: this.headers(source), isLive: false, container: episode.extension ?? extensionOf(url) };
  }
}

function nowSec(): number {
  return Math.floor(nowMillis() / 1000);
}

/** Implements the common M3U catch-up conventions. */
export const CatchupTemplates = {
  build(streamUrl: string, encoded: string | null, startMs: number, endMs: number): string | null {
    const mode = encoded != null ? substringBefore(encoded, '|').toLowerCase() : 'default';
    const rawTemplate = encoded != null ? substringAfter(encoded, '|', '') : '';
    const template = rawTemplate.trim() !== '' ? rawTemplate : null;
    switch (mode) {
      case 'append':
        return template != null ? streamUrl + CatchupTemplates.fill(template, startMs, endMs) : null;
      case 'shift':
      case 'timeshift': {
        const sep = streamUrl.indexOf('?') >= 0 ? '&' : '?';
        return `${streamUrl}${sep}utc=${Math.trunc(startMs / 1000)}&lutc=${nowSec()}`;
      }
      case 'flussonic':
      case 'flussonic-hls':
      case 'fs': {
        const start = Math.trunc(startMs / 1000);
        const dur = Math.trunc((endMs - startMs) / 1000);
        if (streamUrl.indexOf('/index.m3u8') >= 0) return streamUrl.split('/index.m3u8').join(`/index-${start}-${dur}.m3u8`);
        if (streamUrl.indexOf('/mpegts') >= 0) return streamUrl.split('/mpegts').join(`/timeshift_abs-${start}.ts`);
        return null;
      }
      default: {
        if (template == null) return null;
        const filled = CatchupTemplates.fill(template, startMs, endMs);
        return filled.indexOf('://') >= 0 ? filled : streamUrl + filled;
      }
    }
  },

  fill(template: string, startMs: number, endMs: number): string {
    const start = Math.trunc(startMs / 1000);
    const end = Math.trunc(endMs / 1000);
    const dur = end - start;
    const t = fromEpochSeconds(start);
    const e = fromEpochSeconds(end);
    const now = nowSec();
    const ymdhms = (d: typeof t): string =>
      `${d.year}${pad2(d.month)}${pad2(d.day)}${pad2(d.hour)}${pad2(d.minute)}${pad2(d.second)}`;
    const pairs: [string, string][] = [
      ['${start}', String(start)], ['{start}', String(start)], ['{utc}', String(start)],
      ['${end}', String(end)], ['{end}', String(end)], ['{utcend}', String(end)],
      ['${timestamp}', String(now)], ['{lutc}', String(now)], ['${now}', String(now)],
      ['${duration}', String(dur)], ['{duration}', String(dur)],
      ['${offset}', String(now - start)], ['{offset}', String(now - start)],
      ['{Y}', String(t.year)], ['{m}', pad2(t.month)], ['{d}', pad2(t.day)],
      ['{H}', pad2(t.hour)], ['{M}', pad2(t.minute)], ['{S}', pad2(t.second)],
      ['{(b)YmdHMS}', ymdhms(t)], ['{(e)YmdHMS}', ymdhms(e)],
    ];
    let s = template;
    for (const [k, v] of pairs) s = s.split(k).join(v);
    // {duration:60} → minutes
    return s.replace(/\{duration:(\d+)\}/g, (_m, div: string) => {
      const n = parseInt(div, 10);
      return String(Math.trunc(dur / (n > 0 ? n : 1)));
    });
  },
};

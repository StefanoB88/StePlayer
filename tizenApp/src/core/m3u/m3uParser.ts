// Port of m3u/M3uParser.kt: incremental M3U / M3U8 ("extended") parser + classifier.
import { substringAfter, substringAfterLast, substringBefore, toInt32OrNull } from '../util/text.ts';

export interface M3uEntry {
  title: string;
  url: string;
  attributes: { [key: string]: string };
  group: string | null;
  userAgent: string | null;
  referrer: string | null;
}

export interface M3uHeader { epgUrls: string[]; userAgent: string | null }

function attr(e: M3uEntry, key: string): string | undefined {
  return Object.prototype.hasOwnProperty.call(e.attributes, key) ? e.attributes[key] : undefined;
}
function notBlank(v: string | undefined): string | null {
  return v != null && v.trim() !== '' ? v : null;
}

export function tvgId(e: M3uEntry): string | null { return notBlank(attr(e, 'tvg-id')); }
export function tvgName(e: M3uEntry): string | null { return notBlank(attr(e, 'tvg-name')); }
export function logoOf(e: M3uEntry): string | null {
  return notBlank(attr(e, 'tvg-logo') ?? attr(e, 'logo'));
}
export function channelNumber(e: M3uEntry): number | null {
  return toInt32OrNull(attr(e, 'tvg-chno') ?? attr(e, 'channel-number'));
}
export function catchupDays(e: M3uEntry): number {
  return toInt32OrNull(attr(e, 'catchup-days') ?? attr(e, 'timeshift')) ?? 0;
}
export function catchupSource(e: M3uEntry): string | null { return notBlank(attr(e, 'catchup-source')); }
export function catchupMode(e: M3uEntry): string | null {
  const v = attr(e, 'catchup');
  return v == null ? null : v.toLowerCase();
}
export function displayName(e: M3uEntry): string {
  return e.title.trim() !== '' ? e.title : (tvgName(e) ?? substringAfterLast(e.url, '/'));
}

const EMPTY_ATTRS: { [key: string]: string } = {};

function startsWithIgnoreCase(line: string, prefix: string): boolean {
  return line.length >= prefix.length && line.substring(0, prefix.length).toUpperCase() === prefix.toUpperCase();
}

/**
 * Feed it one line at a time; it never holds the whole list in memory, so 200k-entry lists are
 * fine on low-end TVs.
 */
export class M3uParser {
  header: M3uHeader = { epgUrls: [], userAgent: null };
  entryCount = 0;
  private readonly onEntry: (e: M3uEntry) => void;
  private pendingTitle: string | null = null;
  private pendingAttrs: { [key: string]: string } = EMPTY_ATTRS;
  private pendingGroup: string | null = null;
  private pendingUa: string | null = null;
  private pendingRef: string | null = null;

  constructor(onEntry: (e: M3uEntry) => void) {
    this.onEntry = onEntry;
  }

  feed(rawLine: string): void {
    let line = rawLine.trim();
    if (line.charCodeAt(0) === 0xfeff) line = line.substring(1);
    if (line.length === 0) return;
    if (line.charCodeAt(0) === 35 /* # */) {
      if (startsWithIgnoreCase(line, '#EXTM3U')) this.parseHeader(line);
      else if (startsWithIgnoreCase(line, '#EXTINF')) this.parseExtInf(line);
      else if (startsWithIgnoreCase(line, '#EXTGRP:')) {
        const g = substringAfter(line, ':').trim();
        this.pendingGroup = g === '' ? null : g;
      } else if (startsWithIgnoreCase(line, '#EXTVLCOPT:')) this.parseVlcOpt(substringAfter(line, ':'));
      // #KODIPROP, #EXT-X-..., comments: ignored
      return;
    }
    this.emit(line);
  }

  private parseHeader(line: string): void {
    const sp = line.indexOf(' ');
    const attrs = parseAttributes(line, sp < 0 ? line.length : sp);
    const seen: string[] = [];
    for (const key of ['url-tvg', 'x-tvg-url', 'tvg-url']) {
      const v = attrs[key];
      if (v == null) continue;
      for (const part of v.split(',')) {
        const t = part.trim();
        if (t !== '' && seen.indexOf(t) < 0) seen.push(t);
      }
    }
    this.header = { epgUrls: seen, userAgent: attrs['user-agent'] ?? attrs['http-user-agent'] ?? null };
  }

  private parseVlcOpt(opt: string): void {
    const key = substringBefore(opt, '=').trim().toLowerCase();
    const value = substringAfter(opt, '=', '').trim();
    if (key === 'http-user-agent') this.pendingUa = value;
    else if (key === 'http-referrer' || key === 'http-referer') this.pendingRef = value;
  }

  private parseExtInf(line: string): void {
    // #EXTINF:-1 tvg-id="x" group-title="News, World",Display, Name
    let i = line.indexOf(':') + 1;
    if (i <= 0) i = line.length;
    while (i < line.length && line.charCodeAt(i) !== 32 && line.charCodeAt(i) !== 44) i++; // skip duration
    const [attrs, title] = parseAttributesUntilTitle(line, i);
    this.pendingAttrs = attrs;
    this.pendingTitle = title;
    const g = attrs['group-title'];
    if (g != null) this.pendingGroup = g.trim() === '' ? null : g;
  }

  private emit(url: string): void {
    const title = this.pendingTitle;
    if (title == null && url.indexOf('://') < 0) return;
    const entry: M3uEntry = {
      title: title ?? '',
      url,
      attributes: this.pendingAttrs,
      group: this.pendingGroup,
      userAgent: this.pendingUa ?? this.header.userAgent,
      referrer: this.pendingRef,
    };
    this.pendingTitle = null;
    this.pendingAttrs = EMPTY_ATTRS;
    this.pendingGroup = null;
    this.pendingUa = null;
    this.pendingRef = null;
    this.entryCount++;
    this.onEntry(entry);
  }
}

/** Parses key="value" pairs (quotes optional) starting at [from]; stops at the first unquoted comma. */
export function parseAttributesUntilTitle(line: string, from: number): [{ [key: string]: string }, string] {
  const attrs: { [key: string]: string } = {};
  let i = from;
  const n = line.length;
  while (i < n) {
    while (i < n && line.charCodeAt(i) === 32) i++;
    if (i >= n) break;
    if (line.charCodeAt(i) === 44) return [attrs, line.substring(i + 1).trim()];
    const keyStart = i;
    while (i < n) {
      const c = line.charCodeAt(i);
      if (c === 61 || c === 32 || c === 44) break;
      i++;
    }
    const key = line.substring(keyStart, i).toLowerCase();
    if (i < n && line.charCodeAt(i) === 61) {
      i++;
      let value: string;
      const q = line.charAt(i);
      if (i < n && (q === '"' || q === "'")) {
        let end = line.indexOf(q, i + 1);
        if (end < 0) end = n;
        value = line.substring(i + 1, end);
        i = Math.min(end + 1, n);
      } else {
        const start = i;
        while (i < n && line.charCodeAt(i) !== 32 && line.charCodeAt(i) !== 44) i++;
        value = line.substring(start, i);
      }
      if (key !== '') attrs[key] = value.trim();
    }
  }
  return [attrs, ''];
}

export function parseAttributes(line: string, from: number): { [key: string]: string } {
  return parseAttributesUntilTitle(line + ',', from)[0];
}

/** Classification of an M3U entry into the app's three sections. */
export type M3uKind = 'LIVE' | 'MOVIE' | 'SERIES';

const VOD_EXTENSIONS = ['mp4', 'mkv', 'avi', 'mov', 'm4v', 'webm', 'wmv', 'flv', 'mpg', 'mpeg'];
const EPISODE_REGEX = /^(.*?)[\s._\-|]*(?:[Ss](\d{1,2})\s*[Ee](\d{1,3})|(\d{1,2})x(\d{1,3}))/;

export interface EpisodeRef { showName: string; season: number; episode: number }

export const M3uClassifier = {
  classify(entry: M3uEntry): M3uKind {
    const path = substringBefore(entry.url, '?').toLowerCase();
    if (path.indexOf('/series/') >= 0) return 'SERIES';
    if (path.indexOf('/movie/') >= 0) return 'MOVIE';
    const ext = substringAfterLast(path, '.', '');
    if (VOD_EXTENSIONS.indexOf(ext) >= 0) {
      if (M3uClassifier.parseEpisode(displayName(entry)) != null) return 'SERIES';
      // Some lists serve live channels as ".mp4"-like files: guide id, channel number or
      // replay data only make sense for a channel, never for a film.
      const looksLikeChannel = tvgId(entry) != null || channelNumber(entry) != null ||
        catchupDays(entry) > 0 || catchupSource(entry) != null;
      return looksLikeChannel ? 'LIVE' : 'MOVIE';
    }
    return 'LIVE';
  },

  parseEpisode(title: string): EpisodeRef | null {
    const m = EPISODE_REGEX.exec(title);
    if (!m) return null;
    const show = trimEndChars(m[1].trim(), '-|:.').trim();
    const season = toInt32OrNull(m[2] || m[4] || '');
    const ep = toInt32OrNull(m[3] || m[5] || '');
    if (season == null || ep == null) return null;
    if (show === '') return null;
    return { showName: show, season, episode: ep };
  },
};

function trimEndChars(s: string, chars: string): string {
  let end = s.length;
  while (end > 0 && chars.indexOf(s.charAt(end - 1)) >= 0) end--;
  return s.substring(0, end);
}

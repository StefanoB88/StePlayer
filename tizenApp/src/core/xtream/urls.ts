// Port of xtream/XtreamUrls.kt.
import { fromEpochSeconds } from '../util/time.ts';
import { pad2 } from '../util/text.ts';

/** Credentials + server for building API Xtream URLs. Never persisted as a whole. */
export interface XtreamCredentials { baseUrl: string; username: string; password: string }

const DEFAULT_PORTS: { [protocol: string]: string } = { http: '80', https: '443', ws: '80', wss: '443' };

function hexByte(b: number): string {
  return '%' + (b < 16 ? '0' : '') + b.toString(16).toUpperCase();
}

const encoder = new TextEncoder();
function utf8Bytes(ch: string): Uint8Array {
  return encoder.encode(ch);
}

function isAlnum(c: number): boolean {
  return (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122);
}

function isHex(c: number): boolean {
  return (c >= 48 && c <= 57) || (c >= 65 && c <= 70) || (c >= 97 && c <= 102);
}

const PATH_PART_EXTRA = ":@-._~!$&'()*+,;=";

/** Ktor `encodeURLPathPart()`: keeps unreserved, sub-delims, ':' and '@'; encodes '/'. */
export function encodePathPart(value: string): string {
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (isAlnum(c) || PATH_PART_EXTRA.indexOf(value.charAt(i)) >= 0) {
      out += value.charAt(i);
    } else if (c === 37 && i + 2 < value.length && isHex(value.charCodeAt(i + 1)) && isHex(value.charCodeAt(i + 2))) {
      out += value.substring(i, i + 3); // already percent-encoded
      i += 2;
    } else {
      let ch = value.charAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < value.length) { ch += value.charAt(i + 1); i++; }
      for (const b of utf8Bytes(ch)) out += hexByte(b);
    }
  }
  return out;
}

/** Ktor `encodeURLParameter()`: only unreserved characters stay as they are. */
export function encodeParameter(value: string): string {
  let out = '';
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (isAlnum(c) || c === 45 || c === 46 || c === 95 || c === 126) {
      out += value.charAt(i);
    } else {
      let ch = value.charAt(i);
      if (c >= 0xd800 && c <= 0xdbff && i + 1 < value.length) { ch += value.charAt(i + 1); i++; }
      for (const b of utf8Bytes(ch)) out += hexByte(b);
    }
  }
  return out;
}

function parseUrl(s: string): URL | null {
  try {
    return new URL(s);
  } catch {
    return null;
  }
}

function trimEndSlash(s: string): string {
  let end = s.length;
  while (end > 0 && s.charAt(end - 1) === '/') end--;
  return s.substring(0, end);
}

export const XtreamUrls = {
  /**
   * Normalises what users paste: "host:8080", "http://host:8080/", ".../player_api.php?...",
   * ".../get.php?username=..&password=..". Returns "scheme://host[:port]".
   */
  normalizeBaseUrl(input: string): string {
    let s = input.trim();
    if (s.indexOf('://') < 0) s = 'http://' + s;
    const url = parseUrl(s);
    if (!url || !url.hostname) return trimEndSlash(s);
    const protocol = url.protocol.replace(/:$/, '');
    const port = url.port === '' || url.port === DEFAULT_PORTS[protocol] ? '' : ':' + url.port;
    return protocol + '://' + url.hostname + port;
  },

  /**
   * Detects an API Xtream "get.php" M3U link and extracts server + credentials, so that the user
   * gets the much richer API Xtream experience even when pasting an M3U link.
   */
  detectFromM3uUrl(m3uUrl: string): XtreamCredentials | null {
    const url = parseUrl(m3uUrl.trim());
    if (!url) return null;
    if (!/get\.php$/.test(url.pathname)) return null;
    const user = url.searchParams.get('username');
    const pass = url.searchParams.get('password');
    if (user == null || user.trim() === '' || pass == null || pass.trim() === '') return null;
    return { baseUrl: XtreamUrls.normalizeBaseUrl(m3uUrl), username: user, password: pass };
  },

  live(c: XtreamCredentials, streamId: string, ext: string): string {
    return `${c.baseUrl}/live/${encodePathPart(c.username)}/${encodePathPart(c.password)}/${streamId}.${ext}`;
  },

  movie(c: XtreamCredentials, streamId: string, ext: string | null): string {
    return `${c.baseUrl}/movie/${encodePathPart(c.username)}/${encodePathPart(c.password)}/${streamId}.${ext ?? 'mp4'}`;
  },

  episode(c: XtreamCredentials, episodeId: string, ext: string | null): string {
    return `${c.baseUrl}/series/${encodePathPart(c.username)}/${encodePathPart(c.password)}/${episodeId}.${ext ?? 'mp4'}`;
  },

  /**
   * Catch-up (timeshift) URL. The start time must be expressed in the *server's* local time,
   * hence serverOffsetSec (computed at login from server_info).
   */
  timeshift(c: XtreamCredentials, streamId: string, startEpochMs: number, durationMin: number, serverOffsetSec: number): string {
    const t = fromEpochSeconds(Math.floor(startEpochMs / 1000), serverOffsetSec);
    const start = `${t.year}-${pad2(t.month)}-${pad2(t.day)}:${pad2(t.hour)}-${pad2(t.minute)}`;
    return `${c.baseUrl}/timeshift/${encodePathPart(c.username)}/${encodePathPart(c.password)}/${durationMin}/${start}/${streamId}.ts`;
  },

  xmltv(c: XtreamCredentials): string {
    return `${c.baseUrl}/xmltv.php?username=${encodeParameter(c.username)}&password=${encodeParameter(c.password)}`;
  },

  /** player_api.php request (credentials as query parameters, like Ktor's `parameter()`). */
  api(c: XtreamCredentials, action: string | null, params: [string, string][] = []): string {
    let url = `${c.baseUrl}/player_api.php?username=${encodeParameter(c.username)}&password=${encodeParameter(c.password)}`;
    if (action != null) url += `&action=${encodeParameter(action)}`;
    for (const [k, v] of params) url += `&${encodeParameter(k)}=${encodeParameter(v)}`;
    return url;
  },
};

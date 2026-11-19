// Port of xtream/XtreamClient.kt + XtreamDto.kt: thin client over the API Xtream `player_api.php`.
import type { AccountInfo, EpgProgram } from '../api.ts';
import type { Http, HttpResponse } from '../net/http.ts';
import { isSuccess, readText } from '../net/http.ts';
import { NetworkErrors } from '../net/networkErrors.ts';
import { parseSqlDateTime } from '../util/time.ts';
import type { Json } from './flex.ts';
import { field, flexBool, flexDouble, flexInt, flexLong, flexString, isObject } from './flex.ts';
import { JsonArraySplitter, JsonArraySplitterError } from './jsonArraySplitter.ts';
import type { XtreamCredentials } from './urls.ts';
import { XtreamUrls } from './urls.ts';
import { XtreamException } from './xtreamException.ts';

export { XtreamException, isConnectivity } from './xtreamException.ts';
export type { XtreamErrorKind } from './xtreamException.ts';

/**
 * Port of XtreamAuthErrors (XtreamClient.kt). Panels don't agree on how to say "wrong username or
 * password": besides 401/403 and {"user_info":{"auth":0}}, some answer 400/406/4xx, others an
 * error status with a body that says so, others an empty body or [].
 */
export const XtreamAuthErrors = {
  authZero: /["']auth["']\s*:\s*(?:"?0"?|false)/i,
  authWords: /invalid[ _-]?(?:user|login|credential|password|account|auth|username)|(?:wrong|incorrect|bad)[ _-]?(?:user|password|credential|login)|unauthori[sz]ed|authentication[ _-]?failed|auth[ _-]?failed|access[ _-]?denied|user(?:name)?[ _-]?not[ _-]?found|credenziali|not[ _-]?authori[sz]ed/i,
  /** 4xx that are about the request or the server, not about the account. */
  notAboutAccount: [404, 405, 408, 413, 414, 429, 458],

  /** True if an error response (non-2xx) means the credentials were refused. */
  isRefused(status: number, body: string, login: boolean): boolean {
    if (status === 401 || status === 403) return true;
    if (this.authZero.test(body) || this.authWords.test(body)) return true;
    // The login request depends only on address and credentials: any other 4xx is the account.
    return login && status >= 400 && status <= 499 && this.notAboutAccount.indexOf(status) < 0;
  },

  /** True if a 2xx login body is the "nothing for you" answer some panels give to wrong credentials. */
  isEmptyLogin(body: string): boolean {
    const t = body.trim();
    return t === '' || t === '[]' || t === '{}' || t === 'null' || t === '0';
  },

  refused(): XtreamException { return new XtreamException('Username o password non validi.', 'INVALID_CREDENTIALS'); },
};

/** The first few KB of an error response (enough for a message), never the whole body. */
async function errorBody(response: HttpResponse): Promise<string> {
  try {
    const parts: Uint8Array[] = [];
    let size = 0;
    while (size < 4096) {
      const chunk = await response.read();
      if (chunk == null) break;
      parts.push(chunk);
      size += chunk.length;
    }
    const all = new Uint8Array(size);
    let off = 0;
    for (const p of parts) { all.set(p, off); off += p.length; }
    return new TextDecoder('utf-8').decode(all.subarray(0, 4096));
  } catch {
    return '';
  }
}

// ---------------------------------------------------------------- DTOs (lenient)

function has(o: { [k: string]: Json }, key: string): boolean {
  return Object.prototype.hasOwnProperty.call(o, key);
}
/** FlexBool on a nullable property: absent → null, present (even null) → boolean. */
function optBool(o: { [k: string]: Json }, key: string): boolean | null {
  return has(o, key) ? flexBool(o[key]) : null;
}

export interface CategoryDto { categoryId: string | null; categoryName: string | null }
export interface LiveStreamDto {
  num: number | null; name: string | null; streamId: string | null; streamIcon: string | null;
  epgChannelId: string | null; categoryId: string | null; tvArchive: boolean | null; tvArchiveDuration: number | null;
}
export interface VodStreamDto {
  name: string | null; streamId: string | null; streamIcon: string | null; rating: number | null; added: number | null;
  categoryId: string | null; containerExtension: string | null; year: string | null;
}
export interface SeriesDto {
  name: string | null; seriesId: string | null; cover: string | null; plot: string | null; genre: string | null;
  releaseDate: string | null; releaseDate2: string | null; lastModified: number | null; rating: number | null;
  categoryId: string | null; year: string | null;
}

export type DtoDecoder<T> = (o: { [k: string]: Json }) => T;

export const categoryDto: DtoDecoder<CategoryDto> = (o) => ({
  categoryId: flexString(o.category_id), categoryName: flexString(o.category_name),
});

export const liveStreamDto: DtoDecoder<LiveStreamDto> = (o) => ({
  num: flexInt(o.num), name: flexString(o.name), streamId: flexString(o.stream_id), streamIcon: flexString(o.stream_icon),
  epgChannelId: flexString(o.epg_channel_id), categoryId: flexString(o.category_id), tvArchive: optBool(o, 'tv_archive'),
  tvArchiveDuration: flexInt(o.tv_archive_duration),
});

export const vodStreamDto: DtoDecoder<VodStreamDto> = (o) => ({
  name: flexString(o.name), streamId: flexString(o.stream_id), streamIcon: flexString(o.stream_icon),
  rating: flexDouble(o.rating), added: flexLong(o.added), categoryId: flexString(o.category_id),
  containerExtension: flexString(o.container_extension), year: flexString(o.year),
});

export const seriesDto: DtoDecoder<SeriesDto> = (o) => ({
  name: flexString(o.name), seriesId: flexString(o.series_id), cover: flexString(o.cover), plot: flexString(o.plot),
  genre: flexString(o.genre), releaseDate: flexString(o.releaseDate), releaseDate2: flexString(o.release_date),
  lastModified: flexLong(o.last_modified), rating: flexDouble(o.rating), categoryId: flexString(o.category_id),
  year: flexString(o.year),
});

/** Decodes a list body that was already read whole (tests, small responses). */
export function decodeListText<T>(json: string, decoder: DtoDecoder<T>): T[] {
  const out: T[] = [];
  const splitter = new JsonArraySplitter((b, n) => { out.push(decodeElement(b, n, decoder, 'list')); });
  splitter.feed(new TextEncoder().encode(json));
  splitter.finish();
  return out;
}

const utf8 = new TextDecoder('utf-8');

function decodeElement<T>(bytes: Uint8Array, length: number, decoder: DtoDecoder<T>, action: string): T {
  let value: Json;
  try {
    value = JSON.parse(utf8.decode(bytes.subarray(0, length)));
  } catch {
    throw invalidData(action);
  }
  if (!isObject(value)) throw invalidData(action);
  return decoder(value);
}

function invalidData(action: string): XtreamException {
  return new XtreamException(
    `Il server ha inviato dati non validi (${action}). Riprova più tardi; se succede sempre, contatta il provider.`, 'PARSE');
}

/** The login answer isn't JSON from an Xtream panel: typically a web page at the wrong address/port. */
function notXtream(): XtreamException {
  return new XtreamException(
    'Il server ha risposto, ma non come un server compatibile (API Xtream). Controlla indirizzo e porta.', 'PARSE');
}

// ---------------------------------------------------------------- client

/** Result of a successful login. */
export interface XtreamLogin {
  account: AccountInfo;
  /** Seconds to add to UTC to get the server's local time (needed for catch-up). */
  serverOffsetSec: number;
  baseUrl: string;
}

/**
 * All network errors are mapped to XtreamException with a user-presentable kind. The user agent
 * cannot be set from a web page (browsers forbid it), so catalog requests go without it.
 */
export class XtreamClient {
  private readonly http: Http;
  private readonly creds: XtreamCredentials;

  constructor(http: Http, creds: XtreamCredentials) {
    this.http = http;
    this.creds = creds;
  }

  /**
   * Runs block on the streamed response. Network failures — also while reading — become
   * XtreamException with a precise message (NetworkErrors); anything else thrown by block once the
   * answer arrived (e.g. a storage error while persisting items) keeps its own.
   */
  private async call<R>(action: string | null, params: [string, string][], block: (r: HttpResponse) => Promise<R>): Promise<R> {
    let response: HttpResponse | null = null;
    try {
      response = await this.http.get(XtreamUrls.api(this.creds, action, params));
      if (!isSuccess(response.status)) {
        if (XtreamAuthErrors.isRefused(response.status, await errorBody(response), action == null)) throw XtreamAuthErrors.refused();
        throw NetworkErrors.fromStatus(response.status, 'SERVER');
      }
      return await block(response);
    } catch (e) {
      if (response) response.cancel();
      if (e instanceof XtreamException) throw e;
      if (response == null || NetworkErrors.isNetworkFailure(e)) throw NetworkErrors.fromThrowable(e, this.creds.baseUrl, 'SERVER');
      throw e;
    }
  }

  private async decode(action: string | null, params: [string, string][] = []): Promise<Json> {
    const text = await this.call(action, params, (r) => readText(r));
    if (action == null && XtreamAuthErrors.isEmptyLogin(text)) throw XtreamAuthErrors.refused();
    let value: Json;
    try {
      value = JSON.parse(text);
    } catch {
      throw action == null ? notXtream() : invalidData(action);
    }
    if (action == null && !isObject(value)) throw notXtream();
    return value;
  }

  /**
   * Lists may come back as `[]`, `{}` (empty), or `null` on some servers. Decoded in streaming,
   * one array element at a time: a 100k-title catalog never exists in memory as one big body.
   * onItems receives each decoded chunk of items (await it to apply backpressure). onRead, if given,
   * is told the body bytes received so far and the Content-Length (null when unknown), for progress.
   */
  async streamList<T>(
    action: string, decoder: DtoDecoder<T>, onItems: (items: T[]) => Promise<void> | void,
    onRead?: (bytes: number, total: number | null) => void,
  ): Promise<void> {
    await this.call(action, [], async (response) => {
      let pending: T[] = [];
      let bytes = 0;
      const total = response.contentLength != null && response.contentLength > 0 ? response.contentLength : null;
      const splitter = new JsonArraySplitter((b, n) => { pending.push(decodeElement(b, n, decoder, action)); });
      while (!splitter.isDone) {
        const chunk = await response.read();
        if (chunk == null) break;
        bytes += chunk.length;
        if (onRead) onRead(bytes, total);
        splitter.feed(chunk);
        if (pending.length > 0) {
          const items = pending;
          pending = [];
          await onItems(items);
        }
      }
      if (splitter.isDone) response.cancel();
      try {
        splitter.finish();
      } catch (e) {
        if (e instanceof JsonArraySplitterError) throw invalidData(action);
        throw e;
      }
      if (pending.length > 0) await onItems(pending);
    });
  }

  async decodeList<T>(action: string, decoder: DtoDecoder<T>): Promise<T[]> {
    const out: T[] = [];
    await this.streamList(action, decoder, (items) => { for (const i of items) out.push(i); });
    return out;
  }

  async login(): Promise<XtreamLogin> {
    const dto = await this.decode(null);
    const user = field(dto, 'user_info');
    if (!isObject(user) || (has(user, 'auth') && !flexBool(user.auth))) throw XtreamAuthErrors.refused();
    const status = flexString(user.status);
    if (status != null && status.toLowerCase() === 'expired') {
      throw new XtreamException("L'abbonamento risulta scaduto. Rinnovalo presso il provider.", 'ACCOUNT_EXPIRED');
    }
    const server = field(dto, 'server_info');
    const offset = computeServerOffset(flexString(field(server, 'time_now')), flexLong(field(server, 'timestamp_now')));
    const f = user.allowed_output_formats;
    const formats = Array.isArray(f) ? f.map(flexString).filter((x): x is string => x != null) : [];
    const expDate = flexLong(user.exp_date);
    return {
      account: {
        status,
        expiresAt: expDate != null && expDate > 0 ? expDate * 1000 : null,
        maxConnections: flexInt(user.max_connections),
        activeConnections: flexInt(user.active_cons),
        isTrial: optBool(user, 'is_trial') === true,
        allowedFormats: formats,
      },
      serverOffsetSec: offset,
      baseUrl: this.creds.baseUrl,
    };
  }

  liveCategories(): Promise<CategoryDto[]> { return this.decodeList('get_live_categories', categoryDto); }
  vodCategories(): Promise<CategoryDto[]> { return this.decodeList('get_vod_categories', categoryDto); }
  seriesCategories(): Promise<CategoryDto[]> { return this.decodeList('get_series_categories', categoryDto); }

  vodInfo(vodId: string): Promise<Json> { return this.decode('get_vod_info', [['vod_id', vodId]]); }
  seriesInfo(seriesId: string): Promise<Json> { return this.decode('get_series_info', [['series_id', seriesId]]); }

  /** Full EPG of one channel (titles/descriptions are base64 encoded by the server). */
  async channelEpg(streamId: string, epgChannelId: string): Promise<EpgProgram[]> {
    const root = await this.decode('get_simple_data_table', [['stream_id', streamId]]);
    const listings = field(root, 'epg_listings');
    if (!Array.isArray(listings)) return [];
    const out: EpgProgram[] = [];
    for (const item of listings) {
      const start = flexLong(field(item, 'start_timestamp'));
      if (start == null) continue;
      const end = flexLong(field(item, 'stop_timestamp'));
      if (end == null) continue;
      const title = decodeBase64(flexString(field(item, 'title')));
      if (title == null) continue;
      out.push({
        channelEpgId: epgChannelId, startAt: start * 1000, endAt: end * 1000, title,
        description: decodeBase64(flexString(field(item, 'description'))),
      });
    }
    return out;
  }
}

/** Servers report "time_now" in their local zone and "timestamp_now" in UTC. */
export function computeServerOffset(timeNow: string | null, timestampNow: number | null): number {
  if (timeNow == null || timestampNow == null) return 0;
  const localAsUtcMs = parseSqlDateTime(timeNow);
  if (localAsUtcMs == null) return 0;
  const diff = Math.trunc(localAsUtcMs / 1000) - timestampNow;
  // Round to the nearest quarter of an hour to absorb request latency.
  return Math.trunc((diff + (diff >= 0 ? 450 : -450)) / 900) * 900;
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/** Strict RFC 4648 base64 (padding required, like Kotlin's Base64.Default) → bytes, or null. */
function base64Bytes(s: string): Uint8Array | null {
  if (s.length % 4 !== 0) return null;
  let pad = 0;
  if (s.endsWith('==')) pad = 2; else if (s.endsWith('=')) pad = 1;
  const out = new Uint8Array((s.length / 4) * 3 - pad);
  let o = 0;
  for (let i = 0; i < s.length; i += 4) {
    const n: number[] = [];
    for (let k = 0; k < 4; k++) {
      const ch = s.charAt(i + k);
      if (ch === '=') {
        if (i + 4 !== s.length || k < 2) return null;
        n.push(0);
      } else {
        const v = B64.indexOf(ch);
        if (v < 0) return null;
        n.push(v);
      }
    }
    const triple = (n[0] << 18) | (n[1] << 12) | (n[2] << 6) | n[3];
    if (o < out.length) out[o++] = (triple >> 16) & 0xff;
    if (o < out.length) out[o++] = (triple >> 8) & 0xff;
    if (o < out.length) out[o++] = triple & 0xff;
  }
  return out;
}

export function decodeBase64(value: string | null): string | null {
  if (value == null || value.trim() === '') return null;
  const bytes = base64Bytes(value.trim());
  const text = bytes ? new TextDecoder('utf-8').decode(bytes) : value;
  const t = text.trim();
  return t !== '' ? t : null;
}

/** Helpers to read the loosely typed info objects of get_vod_info / get_series_info. */
export const XtreamInfoParser = {
  backdrop(info: Json): string | null {
    const b = field(info, 'backdrop_path');
    return Array.isArray(b) ? flexString(b.length > 0 ? b[0] : null) : flexString(b);
  },

  durationSec(info: Json): number | null {
    const secs = flexInt(field(info, 'duration_secs'));
    if (secs != null) return secs;
    const d = flexString(field(info, 'duration'));
    return d != null ? parseHms(d) : null;
  },

  /** episodes may be `{"1":[...],"2":[...]}` or `[[...],[...]]` or a flat list. */
  episodes(root: Json): Json[] {
    const e = field(root, 'episodes');
    const out: Json[] = [];
    if (isObject(e)) {
      for (const k of Object.keys(e)) {
        const v = e[k];
        if (Array.isArray(v)) for (const x of v) out.push(x);
      }
    } else if (Array.isArray(e)) {
      for (const it of e) {
        if (Array.isArray(it)) for (const x of it) out.push(x); else out.push(it);
      }
    }
    return out;
  },
};

function parseHms(s: string): number | null {
  const p: number[] = [];
  for (const part of s.split(':')) {
    const t = part.trim();
    if (!/^[+-]?\d+$/.test(t)) return null;
    p.push(parseInt(t, 10));
  }
  let total: number;
  if (p.length === 3) total = p[0] * 3600 + p[1] * 60 + p[2];
  else if (p.length === 2) total = p[0] * 60 + p[1];
  else return null;
  return total >= 0 && total <= 2147483647 ? total : null;
}

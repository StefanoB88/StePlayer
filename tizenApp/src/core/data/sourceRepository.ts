// Port of data/SourceRepository.kt.
//
// Secrets (API Xtream password, the M3U list URL which may embed tokens) live in the separate
// "secrets" store and never appear in Source objects, logs or error messages.
import type { Source, SourceInput } from '../api.ts';
import { XtreamClient } from '../xtream/client.ts';
import type { XtreamCredentials } from '../xtream/urls.ts';
import { XtreamUrls } from '../xtream/urls.ts';
import { nowMillis } from '../util/time.ts';
import type { Ctx } from './context.ts';
import { CATALOG_STORES, S, prefixRange } from './db.ts';
import type { SourceRecord } from './records.ts';
import { toSource } from './records.ts';

const KEY_ACTIVE = 'active_source_id';

export function secretKey(id: number): string {
  return `source.${id}.secret`;
}

function blankToNull(s: string | null | undefined): string | null {
  return s == null || s.trim() === '' ? null : s;
}

/** "server.esempio/lista.m3u" works like the API Xtream server field: a missing scheme means http. */
export function withScheme(url: string): string {
  const t = url.trim();
  const slash = t.indexOf('/');
  const host = slash < 0 ? t : t.substring(0, slash);
  // Only when it looks like an address ("server.esempio/…", "10.0.0.2:8080/…"); anything else is
  // left alone so validation still says the URL is not valid.
  const looksLikeHost = host !== '' && (host.indexOf('.') >= 0 || host.indexOf(':') >= 0) && !/\s/.test(host);
  return t === '' || t.indexOf('://') >= 0 || !looksLikeHost ? t : 'http://' + t;
}

export function withSchemes(input: SourceInput): SourceInput {
  if (input.kind !== 'm3u') return input;
  return { ...input, listUrl: withScheme(input.listUrl), epgUrl: input.epgUrl != null ? withScheme(input.epgUrl) : input.epgUrl };
}

/** An M3U link that is actually an API Xtream "get.php" link becomes an API Xtream source. */
export function upgradeIfXtream(input: SourceInput): SourceInput {
  if (input.kind !== 'm3u') return input;
  const detected = XtreamUrls.detectFromM3uUrl(input.listUrl);
  if (!detected) return input;
  return {
    kind: 'xtream', name: input.name, serverUrl: detected.baseUrl, username: detected.username,
    password: detected.password, userAgent: input.userAgent ?? null,
  };
}

export class SourceRepository {
  private readonly ctx: Ctx;
  private readonly slotCache = new Map<number, number>();

  constructor(ctx: Ctx) {
    this.ctx = ctx;
  }

  private async records(): Promise<SourceRecord[]> {
    const all = await this.ctx.db.getAll<SourceRecord>(S.sources);
    return all.sort((a, b) => a.position - b.position || (a.id as number) - (b.id as number));
  }

  async all(): Promise<Source[]> {
    return (await this.records()).map(toSource);
  }

  private async activeId(): Promise<number | null> {
    const v = await this.ctx.db.get<string>(S.settings, KEY_ACTIVE);
    if (v == null || !/^-?\d+$/.test(v)) return null;
    return parseInt(v, 10);
  }

  /** The currently selected list (falls back to the first one). */
  async current(): Promise<Source | null> {
    const [id, all] = await Promise.all([this.activeId(), this.records()]);
    const r = all.filter((s) => s.id === id)[0] ?? all[0];
    return r ? toSource(r) : null;
  }

  async record(id: number): Promise<SourceRecord | null> {
    return (await this.ctx.db.get<SourceRecord>(S.sources, id)) ?? null;
  }

  async byId(id: number): Promise<Source | null> {
    const r = await this.record(id);
    return r ? toSource(r) : null;
  }

  /** Active catalog slot of a source (0 when unknown). Cached: read on every catalog query. */
  async slot(id: number): Promise<number> {
    const cached = this.slotCache.get(id);
    if (cached !== undefined) return cached;
    const r = await this.record(id);
    if (!r) return 0;
    this.slotCache.set(id, r.slot);
    return r.slot;
  }

  forgetSlot(id: number): void {
    this.slotCache.delete(id);
  }

  async setActive(sourceId: number): Promise<void> {
    await this.ctx.db.put(S.settings, String(sourceId), KEY_ACTIVE);
    this.ctx.events.emit('sources');
  }

  /**
   * Validates the input against the server (for API Xtream) and stores it. An M3U link that is
   * actually an API Xtream "get.php" link is upgraded automatically. Returns the new id.
   */
  async add(input: SourceInput): Promise<number> {
    const normalized = upgradeIfXtream(withSchemes(input));
    if (normalized.kind === 'xtream') {
      // Throws XtreamException with a readable message if login fails.
      const creds: XtreamCredentials = {
        baseUrl: XtreamUrls.normalizeBaseUrl(normalized.serverUrl), username: normalized.username.trim(),
        password: normalized.password,
      };
      await new XtreamClient(this.ctx.http, creds).login();
    } else if (normalized.listUrl.trim().indexOf('://') < 0) {
      throw new Error('URL della lista non valido');
    }
    const existing = await this.records();
    const position = existing.reduce((max, s) => Math.max(max, s.position), -1) + 1;
    const base = {
      serverTimeOffsetSec: 0, allowedFormats: [] as string[], expiresAt: null, maxConnections: null, lastSyncAt: null,
      lastEpgSyncAt: null, createdAt: nowMillis(), position, slot: 0,
    };
    const record: SourceRecord = normalized.kind === 'xtream'
      ? {
        ...base, name: normalized.name.trim() === '' ? 'Server' : normalized.name, type: 'XTREAM',
        url: XtreamUrls.normalizeBaseUrl(normalized.serverUrl), username: normalized.username.trim(), epgUrl: null,
        userAgent: blankToNull(normalized.userAgent),
      }
      : {
        ...base, name: normalized.name.trim() === '' ? 'Lista' : normalized.name, type: 'M3U',
        // The full URL may contain tokens: it stays with the secrets.
        url: '', username: null, epgUrl: blankToNull(normalized.epgUrl), userAgent: blankToNull(normalized.userAgent),
      };
    const secret = normalized.kind === 'xtream' ? normalized.password : normalized.listUrl.trim();
    const id = await this.ctx.db.run([S.sources, S.secrets, S.settings], 'readwrite', (tx) => {
      let newId = -1;
      const req = tx.objectStore(S.sources).add(record);
      req.onsuccess = () => {
        newId = req.result as number;
        tx.objectStore(S.secrets).put(secret, secretKey(newId));
        tx.objectStore(S.settings).put(String(newId), KEY_ACTIVE);
      };
      return () => newId;
    });
    this.ctx.events.emit('sources');
    return id;
  }

  async update(id: number, input: SourceInput): Promise<void> {
    const normalized = withSchemes(input);
    await this.ctx.db.run([S.sources, S.secrets], 'readwrite', (tx) => {
      const store = tx.objectStore(S.sources);
      const req = store.get(id);
      req.onsuccess = () => {
        const existing = req.result as SourceRecord | undefined;
        if (!existing) return;
        if (normalized.kind === 'xtream') {
          store.put({
            ...existing, name: normalized.name, url: XtreamUrls.normalizeBaseUrl(normalized.serverUrl),
            username: normalized.username.trim(), userAgent: blankToNull(normalized.userAgent),
          });
          if (normalized.password !== '') tx.objectStore(S.secrets).put(normalized.password, secretKey(id));
        } else {
          store.put({
            ...existing, name: normalized.name, url: '', username: null, epgUrl: blankToNull(normalized.epgUrl),
            userAgent: blankToNull(normalized.userAgent),
          });
          if (normalized.listUrl.trim() !== '') tx.objectStore(S.secrets).put(normalized.listUrl.trim(), secretKey(id));
        }
      };
      return () => undefined;
    });
    this.ctx.events.emit('sources');
  }

  async delete(id: number): Promise<void> {
    this.forgetSlot(id);
    const stores = [S.sources, S.secrets, S.settings, S.categoryPrefs, S.epg, S.favorites, S.progress, S.search, ...CATALOG_STORES];
    await this.ctx.db.run(stores, 'readwrite', (tx) => {
      tx.objectStore(S.sources).delete(id);
      tx.objectStore(S.secrets).delete(secretKey(id));
      // Cascade to every catalog table (both slots), guide, favorites, progress and search index.
      const all = prefixRange([id]);
      for (const s of [S.categoryPrefs, S.epg, S.favorites, S.progress, ...CATALOG_STORES]) tx.objectStore(s).delete(all);
      tx.objectStore(S.search).delete([id, 'catalog']);
      tx.objectStore(S.search).delete([id, 'epg']);
      const settings = tx.objectStore(S.settings);
      const r = settings.get(KEY_ACTIVE);
      r.onsuccess = () => { if (r.result === String(id)) settings.delete(KEY_ACTIVE); };
      return () => undefined;
    });
    this.ctx.events.emit('sources');
    this.ctx.events.emit('catalog');
    this.ctx.events.emit('epg');
    this.ctx.events.emit('library');
  }

  /** Values to pre-fill the edit form (secrets included). */
  async inputFor(id: number): Promise<SourceInput | null> {
    const r = await this.record(id);
    if (!r) return null;
    const secret = (await this.ctx.db.get<string>(S.secrets, secretKey(id))) ?? '';
    if (r.type === 'XTREAM') {
      return { kind: 'xtream', name: r.name, serverUrl: r.url, username: r.username ?? '', password: secret, userAgent: r.userAgent };
    }
    return { kind: 'm3u', name: r.name, listUrl: secret, epgUrl: r.epgUrl, userAgent: r.userAgent };
  }

  async xtreamCredentials(source: Source | SourceRecord): Promise<XtreamCredentials | null> {
    if (source.type !== 'XTREAM') return null;
    const password = await this.ctx.db.get<string>(S.secrets, secretKey(source.id as number));
    if (password == null) return null;
    // Source.url is the API Xtream base URL (records and models agree for XTREAM).
    return { baseUrl: source.url, username: source.username ?? '', password };
  }

  async m3uUrl(source: Source | SourceRecord): Promise<string | null> {
    if (source.type !== 'M3U') return null;
    return (await this.ctx.db.get<string>(S.secrets, secretKey(source.id as number))) ?? null;
  }

  /** Read-modify-write of a source record in one transaction. */
  async patch(id: number, change: (r: SourceRecord) => SourceRecord | null): Promise<void> {
    await this.ctx.db.run(S.sources, 'readwrite', (tx) => {
      const store = tx.objectStore(S.sources);
      const req = store.get(id);
      req.onsuccess = () => {
        const r = req.result as SourceRecord | undefined;
        if (!r) return;
        const next = change(r);
        if (next) store.put(next);
      };
      return () => undefined;
    });
  }
}

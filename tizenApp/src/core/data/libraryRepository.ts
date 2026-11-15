// Port of data/LibraryRepository.kt: favorites, watch progress ("continue watching") and recently
// watched channels.
import type { ContentType, WatchProgress } from '../api.ts';
import { nowMillis } from '../util/time.ts';
import type { Ctx } from './context.ts';
import { S, boundRange, prefixRange } from './db.ts';
import type { FavoriteRecord, ProgressRecord } from './records.ts';
import { toProgress } from './records.ts';

export class LibraryRepository {
  private readonly ctx: Ctx;

  constructor(ctx: Ctx) {
    this.ctx = ctx;
  }

  async isFavorite(sourceId: number, type: ContentType, itemId: string): Promise<boolean> {
    return (await this.ctx.db.get(S.favorites, [sourceId, type, itemId])) != null;
  }

  async favoriteIds(sourceId: number, type: ContentType): Promise<Set<string>> {
    const rows = await this.ctx.db.getAll<FavoriteRecord>(S.favorites, prefixRange([sourceId, type]));
    return new Set(rows.map((r) => r.id));
  }

  /** Favorites of a type, newest first (Kotlin: ORDER BY f.created_at DESC). */
  async favorites(sourceId: number, type: ContentType): Promise<FavoriteRecord[]> {
    const rows = await this.ctx.db.getAll<FavoriteRecord>(S.favorites, prefixRange([sourceId, type]));
    return rows.sort((a, b) => b.createdAt - a.createdAt);
  }

  async toggleFavorite(sourceId: number, type: ContentType, itemId: string): Promise<boolean> {
    const result = await this.ctx.db.run(S.favorites, 'readwrite', (tx) => {
      const store = tx.objectStore(S.favorites);
      let added = false;
      const req = store.get([sourceId, type, itemId]);
      req.onsuccess = () => {
        if (req.result != null) {
          store.delete([sourceId, type, itemId]);
        } else {
          const rec: FavoriteRecord = { s: sourceId, t: type, id: itemId, createdAt: nowMillis() };
          store.put(rec);
          added = true;
        }
      };
      return () => added;
    });
    this.ctx.events.emit('library');
    return result;
  }

  async saveProgress(p: Omit<WatchProgress, 'updatedAt'>): Promise<void> {
    const rec: ProgressRecord = {
      s: p.sourceId, t: p.type, id: p.itemId, parentId: p.parentId, title: p.title, subtitle: p.subtitle,
      image: p.image, positionMs: p.positionMs, durationMs: p.durationMs, updatedAt: nowMillis(),
    };
    await this.ctx.db.put(S.progress, rec);
    this.ctx.events.emit('library');
  }

  async progress(sourceId: number, type: ContentType, itemId: string): Promise<WatchProgress | null> {
    const r = await this.ctx.db.get<ProgressRecord>(S.progress, [sourceId, type, itemId]);
    return r ? toProgress(r) : null;
  }

  async seriesProgress(sourceId: number, seriesId: string): Promise<Map<string, WatchProgress>> {
    const rows = await this.ctx.db.getAll<ProgressRecord>(S.progress, prefixRange([sourceId, 'EPISODE']));
    const out = new Map<string, WatchProgress>();
    for (const r of rows) if (r.parentId === seriesId) out.set(r.id, toProgress(r));
    return out;
  }

  /** Most recent first, filtered. */
  private async recent(sourceId: number, limit: number, accept: (r: ProgressRecord) => boolean): Promise<WatchProgress[]> {
    const out: WatchProgress[] = [];
    if (limit <= 0) return out;
    const range = boundRange([sourceId, -Infinity], [sourceId, Infinity]);
    await this.ctx.db.scan<ProgressRecord>(S.progress, range, { index: 'updated', direction: 'prev' }, (r) => {
      if (accept(r)) out.push(toProgress(r));
      return out.length < limit;
    });
    return out;
  }

  continueWatching(sourceId: number, limit = 20): Promise<WatchProgress[]> {
    return this.recent(sourceId, limit, (r) =>
      (r.t === 'MOVIE' || r.t === 'EPISODE') && r.positionMs > 30_000 &&
      (r.durationMs === 0 || r.positionMs < r.durationMs * 0.95));
  }

  recentChannels(sourceId: number, limit = 20): Promise<WatchProgress[]> {
    return this.recent(sourceId, limit, (r) => r.t === 'LIVE');
  }

  async removeProgress(sourceId: number, type: ContentType, itemId: string): Promise<void> {
    await this.ctx.db.delete(S.progress, [sourceId, type, itemId]);
    this.ctx.events.emit('library');
  }
}

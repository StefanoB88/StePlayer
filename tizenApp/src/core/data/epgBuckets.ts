// Guide storage layout: one IndexedDB record per (source, channel, UTC day of start) holding that
// day's programmes sorted by start. IndexedDB writes are the bottleneck on TV hardware: a bucket
// cuts the record count ~25x compared to one record per programme (Kotlin's epg_program rows),
// and now/next reads one or two records per channel.
import { DAY } from '../util/time.ts';
import type { Db } from './db.ts';
import { S, boundRange, prefixRange } from './db.ts';

export interface EpgItem { start: number; end: number; title: string; desc: string | null }
export interface EpgBucket { s: number; c: string; d: number; items: EpgItem[] }

export function dayOf(ms: number): number {
  return Math.floor(ms / DAY);
}

/** Sorted by start; for equal starts the item added last wins (like a primary-key overwrite). */
export function normalizeItems(items: EpgItem[]): EpgItem[] {
  const indexed = items.map((item, i) => ({ item, i }));
  indexed.sort((a, b) => a.item.start - b.item.start || a.i - b.i);
  const out: EpgItem[] = [];
  for (const { item } of indexed) {
    if (out.length > 0 && out[out.length - 1].start === item.start) out[out.length - 1] = item;
    else out.push(item);
  }
  return out;
}

/** Accumulates programmes per bucket and merges them into the store in batched transactions. */
export class BucketWriter {
  private readonly db: Db;
  private readonly sourceId: number;
  private pending = new Map<string, EpgBucket>();
  private count = 0;

  constructor(db: Db, sourceId: number) {
    this.db = db;
    this.sourceId = sourceId;
  }

  get size(): number { return this.count; }

  add(channel: string, item: EpgItem): void {
    const d = dayOf(item.start);
    const key = channel + '\n' + d;
    let b = this.pending.get(key);
    if (!b) {
      b = { s: this.sourceId, c: channel, d, items: [] };
      this.pending.set(key, b);
    }
    b.items.push(item);
    this.count++;
  }

  /** Writes pending buckets (merging with what is stored); [clearFirst] wipes the source's guide in the same transaction. */
  flush(clearFirst: boolean): Promise<void> {
    const buckets = Array.from(this.pending.values());
    this.pending = new Map();
    this.count = 0;
    const s = this.sourceId;
    return this.db.run(S.epg, 'readwrite', (tx) => {
      const store = tx.objectStore(S.epg);
      if (clearFirst) store.delete(prefixRange([s]));
      for (const b of buckets) {
        const r = store.get([s, b.c, b.d]);
        r.onsuccess = () => {
          const existing = r.result as EpgBucket | undefined;
          const items = existing ? existing.items.concat(b.items) : b.items;
          store.put({ s, c: b.c, d: b.d, items: normalizeItems(items) });
        };
      }
      return () => undefined;
    });
  }
}

/** Buckets that may hold programmes overlapping [from, to) for each channel (one transaction). */
export function bucketRanges(sourceId: number, epgIds: string[], from: number, to: number, maxDuration: number): IDBKeyRange[] {
  const d0 = dayOf(from - maxDuration);
  const d1 = dayOf(to);
  return epgIds.map((id) => boundRange([sourceId, id, d0], [sourceId, id, d1]));
}

/** Flattens buckets into programmes overlapping [from, to), ordered by start. */
export function itemsIn(buckets: EpgBucket[], from: number, to: number): EpgItem[] {
  const out: EpgItem[] = [];
  for (const b of buckets) for (const it of b.items) if (it.end > from && it.start < to) out.push(it);
  return out; // buckets come in day order and items are sorted inside each bucket
}

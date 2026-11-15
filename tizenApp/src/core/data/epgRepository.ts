// Port of data/EpgRepository.kt.
import type { Channel, ChannelWithEpg, EpgProgram, Source } from '../api.ts';
import { readTextStream } from '../epg/byteStreams.ts';
import { XmltvParser } from '../epg/xmltvParser.ts';
import { isSuccess } from '../net/http.ts';
import { EpgIndexBuilder } from '../search/searchIndex.ts';
import { logCore } from '../util/emitter.ts';
import { DAY, HOUR, nowMillis } from '../util/time.ts';
import { XtreamClient } from '../xtream/client.ts';
import { XtreamUrls } from '../xtream/urls.ts';
import type { Ctx } from './context.ts';
import { S, prefixRange } from './db.ts';
import type { EpgBucket, EpgItem } from './epgBuckets.ts';
import { BucketWriter, bucketRanges, dayOf, itemsIn, normalizeItems } from './epgBuckets.ts';
import type { ChannelRecord, SourceRecord } from './records.ts';
import { toProgram } from './records.ts';
import type { SourceRepository } from './sourceRepository.ts';

/** Programmes buffered before a write (grouped into per-channel-per-day buckets). */
const BATCH = 5000;
/** Kept guide window: past = longest archive among the channels (max 7 days), future = 3 days. */
const MAX_ARCHIVE_DAYS = 7;
const FUTURE_DAYS = 3;
/** Fallback for the "started before the window" lookup when the longest programme is unknown. */
const DEFAULT_MAX_DURATION = DAY;
const MAX_DURATION_CAP = 7 * DAY;

export class EpgRepository {
  private readonly ctx: Ctx;
  private readonly sources: SourceRepository;

  constructor(ctx: Ctx, sources: SourceRepository) {
    this.ctx = ctx;
    this.sources = sources;
  }

  /**
   * Imports the XMLTV guide of a source, streaming (plain or gzip). Only programmes for channels
   * that exist in the list and that fall in [now - archive, now + 3 days] are kept.
   */
  async refresh(source: Source): Promise<void> {
    const record = await this.sources.record(source.id);
    if (!record) return;
    let url: string | null = record.epgUrl;
    if (record.type === 'XTREAM' && url == null) {
      const creds = await this.sources.xtreamCredentials(record);
      url = creds ? XtreamUrls.xmltv(creds) : null;
    }
    if (url == null) return;

    // Wanted guide ids (canonical strings, so stored records never pin parser buffers) + longest archive.
    const wanted = new Map<string, string>();
    let maxArchive = 0;
    await this.ctx.db.scan<ChannelRecord>(S.channels, prefixRange([record.id as number, record.slot]), {}, (c) => {
      if (c.epg != null && c.epg.trim() !== '' && !wanted.has(c.epg)) wanted.set(c.epg, c.epg);
      if (c.arch > maxArchive) maxArchive = c.arch;
      return true;
    });
    if (wanted.size === 0) return;
    const sourceId = record.id as number;
    const now = nowMillis();
    const from = now - Math.min(MAX_ARCHIVE_DAYS, Math.max(0, maxArchive)) * DAY;
    const to = now + FUTURE_DAYS * DAY;

    const batch = new BucketWriter(this.ctx.db, sourceId);
    let first = true;
    let touched = false;
    let maxDuration = 0;
    const index = new EpgIndexBuilder();

    const flush = async (): Promise<void> => {
      if (batch.size === 0 && !first) return;
      const clear = first;
      first = false;
      await batch.flush(clear); // the first write also drops the previous guide (same transaction)
      touched = true;
    };

    try {
      const response = await this.ctx.http.get(url);
      if (!isSuccess(response.status)) {
        response.cancel();
        throw new Error(`EPG HTTP ${response.status}`);
      }
      const parser = new XmltvParser((p) => {
        if (p.stop > from && p.start < to) {
          const c = wanted.get(p.channel) as string;
          batch.add(c, { start: p.start, end: p.stop, title: p.title, desc: p.description });
          index.add(c, p.start, p.stop, p.title, p.description);
          if (p.stop - p.start > maxDuration) maxDuration = p.stop - p.start;
        }
      }, undefined, (id) => wanted.has(id));
      await readTextStream(response, async (text) => {
        parser.feed(text);
        if (batch.size >= BATCH) await flush();
      }, this.ctx.textOptions);
      parser.finish();
      if (!first || batch.size > 0) await flush();

      if (first) {
        // Nothing imported: the old guide stays, minus what fell out of the window.
        await this.deleteBefore(sourceId, from);
        touched = true;
      }
      try {
        if (!first) await this.ctx.db.put(S.search, index.build(), [sourceId, 'epg']);
        else await this.ctx.db.delete(S.search, [sourceId, 'epg']); // rebuilt lazily from the store
      } catch (e) {
        logCore('guide search index not saved', e);
      }
      const imported = !first;
      await this.sources.patch(sourceId, (r) => ({
        ...r,
        lastEpgSyncAt: nowMillis(),
        epgMaxDurMs: imported ? Math.min(MAX_DURATION_CAP, maxDuration) : r.epgMaxDurMs,
      }));
      this.ctx.events.emit('sources');
    } finally {
      // Listeners are notified exactly once, even if the import failed half way.
      if (touched) this.ctx.events.emit('epg');
    }
  }

  private async deleteBefore(sourceId: number, before: number): Promise<void> {
    await this.ctx.db.run(S.epg, 'readwrite', (tx) => {
      const req = tx.objectStore(S.epg).openCursor(prefixRange([sourceId]));
      req.onsuccess = () => {
        const cursor = req.result;
        if (!cursor) return;
        const b = cursor.value as EpgBucket;
        const items = b.items.filter((it) => !(it.end < before));
        if (items.length === 0) cursor.delete();
        else if (items.length !== b.items.length) cursor.update({ ...b, items });
        cursor.continue();
      };
      return () => undefined;
    });
  }

  private maxDurationOf(record: SourceRecord | null): number {
    const d = record?.epgMaxDurMs;
    return d != null && d > 0 ? Math.min(d, MAX_DURATION_CAP) : DEFAULT_MAX_DURATION;
  }

  /** Programmes overlapping [from, to) for many channels, ordered by start (one transaction). */
  private async window(sourceId: number, epgIds: string[], from: number, to: number): Promise<EpgItem[][]> {
    const margin = this.maxDurationOf(await this.sources.record(sourceId));
    const rows = await this.ctx.db.getAllMany<EpgBucket>(S.epg, bucketRanges(sourceId, epgIds, from, to, margin));
    return rows.map((buckets) => itemsIn(buckets, from, to));
  }

  /** Guide for one channel. Falls back to the API Xtream per-channel call if the XMLTV had nothing. */
  async programs(channel: Channel, from: number, to: number): Promise<EpgProgram[]> {
    const epgId = channel.epgId;
    if (epgId == null) return [];
    const [local] = await this.window(channel.sourceId, [epgId], from, to);
    if (local.length > 0) return local.map((r) => toProgram(epgId, r));
    const source = await this.sources.byId(channel.sourceId);
    if (!source) return [];
    const creds = await this.sources.xtreamCredentials(source);
    if (!creds) return [];
    let remote: EpgProgram[] = [];
    try {
      remote = await new XtreamClient(this.ctx.http, creds).channelEpg(channel.id, epgId);
    } catch (e) {
      logCore('per-channel guide unavailable', e);
    }
    if (remote.length > 0) {
      const sourceId = channel.sourceId;
      let maxDuration = 0;
      const byDay = new Map<number, EpgItem[]>();
      for (const p of remote) {
        const d = dayOf(p.startAt);
        const list = byDay.get(d) ?? [];
        list.push({ start: p.startAt, end: p.endAt, title: p.title, desc: p.description });
        byDay.set(d, list);
        if (p.endAt - p.startAt > maxDuration) maxDuration = p.endAt - p.startAt;
      }
      await this.ctx.db.run(S.epg, 'readwrite', (tx) => {
        const store = tx.objectStore(S.epg);
        store.delete(prefixRange([sourceId, epgId]));
        byDay.forEach((items, d) => { store.put({ s: sourceId, c: epgId, d, items: normalizeItems(items) }); });
        return () => undefined;
      });
      await this.sources.patch(sourceId, (r) => (
        maxDuration > (r.epgMaxDurMs ?? 0) ? { ...r, epgMaxDurMs: Math.min(MAX_DURATION_CAP, maxDuration) } : null
      ));
      this.ctx.events.emit('epg');
    }
    return remote.filter((p) => p.endAt > from && p.startAt < to);
  }

  /**
   * Now/next for a list of channels (channel lists, mini-guide). Any number of channels is fine.
   * On a storage failure it returns the channels without guide data instead of failing.
   */
  async nowNext(sourceId: number, channels: Channel[], now: number = nowMillis()): Promise<ChannelWithEpg[]> {
    const noGuide = (): ChannelWithEpg[] => channels.map((ch) => ({ channel: ch, now: null, next: null }));
    const ids: string[] = [];
    const seen = new Set<string>();
    for (const ch of channels) {
      if (ch.epgId != null && !seen.has(ch.epgId)) { seen.add(ch.epgId); ids.push(ch.epgId); }
    }
    if (ids.length === 0) return noGuide();
    try {
      const rows = await this.window(sourceId, ids, now, now + 6 * HOUR);
      const byChannel = new Map<string, EpgProgram[]>();
      ids.forEach((id, i) => byChannel.set(id, rows[i].map((r) => toProgram(id, r, false))));
      return channels.map((ch) => {
        const list = (ch.epgId != null ? byChannel.get(ch.epgId) : undefined) ?? [];
        const current = list.filter((p) => now >= p.startAt && now < p.endAt)[0] ?? null;
        const threshold = current ? current.endAt : now;
        const next = list.filter((p) => p.startAt >= threshold)[0] ?? null;
        return { channel: ch, now: current, next };
      });
    } catch (e) {
      logCore('now/next unavailable', e);
      return noGuide();
    }
  }

  /** Programmes of many channels in a time window (grid guide). Descriptions are left out. */
  async guide(sourceId: number, epgIds: string[], from: number, to: number): Promise<Record<string, EpgProgram[]>> {
    const out: Record<string, EpgProgram[]> = {};
    const seen = new Set<string>();
    const ids = epgIds.filter((id) => (seen.has(id) ? false : (seen.add(id), true)));
    if (ids.length === 0) return out;
    const rows = await this.window(sourceId, ids, from, to);
    ids.forEach((id, i) => {
      if (rows[i].length > 0) out[id] = rows[i].map((r) => toProgram(id, r, false));
    });
    return out;
  }
}

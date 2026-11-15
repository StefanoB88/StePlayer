// Replacement for Kotlin's per-source FTS4 tables (search/SearchIndex.kt) on IndexedDB.
//
// Catalog: one compact string per source, one line per item ("\n" + doc0 + "\n" + doc1 …), each
// line holding the item's normalised tokens separated by single spaces. Line i is the item at
// position i (channels first, then movies, then series), so no id table is needed. A prefix query
// is a native indexOf() over the string plus a word-start check: fast and tiny next to an
// inverted index, which a TV's RAM can't afford for 170k titles.
//
// Guide: unique token lines (repeated shows share one line) + typed arrays per programme
// (line, channel, start, end). Descriptions are indexed only while the text stays under a
// budget (small guides): big guides are searched by title, see DESC_BUDGET_CHARS.
//
// Both are built while syncing (no second pass over the data), persisted in the "search" store
// and loaded lazily on the first search.
import { normalizeForSearch } from '../util/text.ts';
import { logCore } from '../util/emitter.ts';
import type { Ctx } from '../data/context.ts';
import { S, prefixRange } from '../data/db.ts';
import type { EpgBucket } from '../data/epgBuckets.ts';
import type { ChannelRecord, MovieRecord, SeriesRecord } from '../data/records.ts';
import type { SourceRepository } from '../data/sourceRepository.ts';
import { QueryParser, editDistance, tokenizeNormalized } from './queryParser.ts';

/** Max characters of unique guide text with descriptions; beyond it the guide is indexed by title only. */
export const DESC_BUDGET_CHARS = 8_000_000;

function tokensLine(norm: string): string {
  return tokenizeNormalized(norm).join(' ');
}

export function channelDoc(searchName: string, num: number | null): string {
  return tokensLine(searchName + ' ' + (num != null ? String(num) : ''));
}
export function movieDoc(searchName: string): string {
  return tokensLine(searchName);
}
export function seriesDoc(searchName: string, genre: string | null, plot: string | null): string {
  return tokensLine(searchName + ' ' + normalizeForSearch((genre ?? '') + ' ' + (plot ?? '').substring(0, 600)));
}

export interface CatalogBlob { g: number; text: string; counts: number[] }

export interface EpgBlob {
  docs: string;
  epgIds: string[];
  progDoc: Int32Array;
  progEpg: Int32Array;
  progStart: Float64Array;
  progEnd: Float64Array;
  withDescriptions: boolean;
}

/** Builds the catalog blob during a sync (one doc per inserted item, in position order). */
export class CatalogIndexBuilder {
  private readonly channels: string[] = [];
  private readonly movies: string[] = [];
  private readonly series: string[] = [];

  channel(pos: number, searchName: string, num: number | null): void { put(this.channels, pos, channelDoc(searchName, num)); }
  movie(pos: number, searchName: string): void { put(this.movies, pos, movieDoc(searchName)); }
  seriesItem(pos: number, searchName: string, genre: string | null, plot: string | null): void {
    put(this.series, pos, seriesDoc(searchName, genre, plot));
  }

  build(g: number): CatalogBlob {
    const all = this.channels.concat(this.movies, this.series);
    return { g, text: '\n' + all.join('\n'), counts: [this.channels.length, this.movies.length, this.series.length] };
  }
}

function put(list: string[], pos: number, doc: string): void {
  while (list.length < pos) list.push('');
  list[pos] = doc;
}

/** Builds the guide blob while the XMLTV is imported. */
export class EpgIndexBuilder {
  private readonly epgIndex = new Map<string, number>();
  private readonly epgIds: string[] = [];
  private readonly titleDocs = new Map<string, number>();
  private fullDocs: Map<string, number> | null = new Map();
  private fullChars = 0;
  private readonly progTitle: number[] = [];
  private readonly progFull: number[] = [];
  private readonly progEpg: number[] = [];
  private readonly progStart: number[] = [];
  private readonly progEnd: number[] = [];

  get size(): number { return this.progStart.length; }

  add(epgId: string, start: number, end: number, title: string, description: string | null): void {
    let e = this.epgIndex.get(epgId);
    if (e === undefined) {
      e = this.epgIds.length;
      this.epgIds.push(epgId);
      this.epgIndex.set(epgId, e);
    }
    const titleLine = tokensLine(normalizeForSearch(title));
    let t = this.titleDocs.get(titleLine);
    if (t === undefined) { t = this.titleDocs.size; this.titleDocs.set(titleLine, t); }
    this.progTitle.push(t);
    if (this.fullDocs) {
      const full = description ? tokensLine(normalizeForSearch(title + ' ' + description.substring(0, 400))) : titleLine;
      let f = this.fullDocs.get(full);
      if (f === undefined) {
        f = this.fullDocs.size;
        this.fullDocs.set(full, f);
        this.fullChars += full.length + 1;
        if (this.fullChars > DESC_BUDGET_CHARS) this.fullDocs = null; // too big for RAM: titles only
      }
      this.progFull.push(f);
    }
    this.progEpg.push(e);
    this.progStart.push(start);
    this.progEnd.push(end);
  }

  build(): EpgBlob {
    const withDescriptions = this.fullDocs != null;
    const docsMap = this.fullDocs ?? this.titleDocs;
    const lines: string[] = new Array(docsMap.size);
    docsMap.forEach((i, line) => { lines[i] = line; });
    return {
      docs: '\n' + lines.join('\n'),
      epgIds: this.epgIds.slice(),
      progDoc: Int32Array.from(withDescriptions ? this.progFull : this.progTitle),
      progEpg: Int32Array.from(this.progEpg),
      progStart: Float64Array.from(this.progStart),
      progEnd: Float64Array.from(this.progEnd),
      withDescriptions,
    };
  }
}

/** Lines of normalised tokens with prefix search (the FTS MATCH replacement). */
export class TextDocs {
  readonly text: string;
  readonly starts: Int32Array;

  constructor(text: string) {
    this.text = text;
    const starts: number[] = [];
    let i = text.indexOf('\n');
    while (i >= 0) {
      starts.push(i + 1);
      i = text.indexOf('\n', i + 1);
    }
    this.starts = Int32Array.from(starts);
  }

  get count(): number { return this.starts.length; }

  docAt(offset: number): number {
    const s = this.starts;
    let lo = 0, hi = s.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (s[mid] <= offset) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  docText(i: number): string {
    const end = i + 1 < this.starts.length ? this.starts[i + 1] - 1 : this.text.length;
    return this.text.substring(this.starts[i], end);
  }

  /** Docs where every stem prefixes some word (implicit AND), ascending; at most [limit]. */
  match(stems: string[], limit: number): number[] {
    const out: number[] = [];
    if (stems.length === 0 || this.starts.length === 0) return out;
    let driver = stems[0];
    for (const s of stems) if (s.length > driver.length) driver = s;
    const others = stems.filter((s) => s !== driver).map((s) => ' ' + s);
    const text = this.text;
    let from = 0;
    while (out.length < limit) {
      const p = text.indexOf(driver, from);
      if (p < 0) break;
      const prev = text.charCodeAt(p - 1);
      if (prev !== 32 && prev !== 10) { from = p + 1; continue; }
      const doc = this.docAt(p);
      const end = doc + 1 < this.starts.length ? this.starts[doc + 1] - 1 : text.length;
      let ok = true;
      if (others.length > 0) {
        const line = ' ' + text.substring(this.starts[doc], end);
        for (const o of others) if (line.indexOf(o) < 0) { ok = false; break; }
      }
      if (ok) out.push(doc);
      from = end; // next doc
    }
    return out;
  }

  /** True if some word starts with [prefix]. */
  hasPrefix(prefix: string): boolean {
    const text = this.text;
    let from = 0;
    for (;;) {
      const p = text.indexOf(prefix, from);
      if (p < 0) return false;
      const prev = text.charCodeAt(p - 1);
      if (prev === 32 || prev === 10) return true;
      from = p + 1;
    }
  }

  /** Term → number of documents containing it (fts4aux "documents"); weights per doc optional. */
  vocabulary(weights?: Int32Array): Map<string, number> {
    const vocab = new Map<string, number>();
    for (let d = 0; d < this.starts.length; d++) {
      const w = weights ? weights[d] : 1;
      if (w === 0) continue;
      const line = this.docText(d);
      if (line.length === 0) continue;
      const words = line.split(' ');
      for (let k = 0; k < words.length; k++) {
        const word = words[k];
        if (word === '' || words.indexOf(word) < k) continue; // once per doc
        vocab.set(word, (vocab.get(word) ?? 0) + w);
      }
    }
    return vocab;
  }
}

export interface LoadedCatalog { g: number; docs: TextDocs; counts: number[]; vocab?: Map<string, number> }
export interface LoadedEpg { blob: EpgBlob; docs: TextDocs; vocab?: Map<string, number> }

export class SearchIndex {
  private readonly ctx: Ctx;
  private readonly sources: SourceRepository;
  private catalogCache: { sourceId: number; value: LoadedCatalog } | null = null;
  private epgCache: { sourceId: number; value: LoadedEpg | null } | null = null;

  constructor(ctx: Ctx, sources: SourceRepository) {
    this.ctx = ctx;
    this.sources = sources;
    ctx.events.on('catalog', () => { this.catalogCache = null; });
    ctx.events.on('epg', () => { this.epgCache = null; });
    ctx.events.on('sources', () => { this.catalogCache = null; this.epgCache = null; });
  }

  async catalog(sourceId: number): Promise<LoadedCatalog | null> {
    if (this.catalogCache && this.catalogCache.sourceId === sourceId) return this.catalogCache.value;
    const record = await this.sources.record(sourceId);
    if (!record) return null;
    let blob = await this.ctx.db.get<CatalogBlob>(S.search, [sourceId, 'catalog']);
    if (!blob || blob.g !== record.slot) {
      // Missing or stale (e.g. interrupted sync): rebuild from the stored catalog.
      blob = await this.rebuildCatalog(sourceId, record.slot);
    }
    const value: LoadedCatalog = { g: blob.g, docs: new TextDocs(blob.text), counts: blob.counts };
    this.catalogCache = { sourceId, value };
    return value;
  }

  async epg(sourceId: number): Promise<LoadedEpg | null> {
    if (this.epgCache && this.epgCache.sourceId === sourceId) return this.epgCache.value;
    let blob = await this.ctx.db.get<EpgBlob>(S.search, [sourceId, 'epg']);
    if (!blob) blob = await this.rebuildEpg(sourceId);
    const value = blob && blob.progStart.length > 0 ? { blob, docs: new TextDocs(blob.docs) } : null;
    this.epgCache = { sourceId, value };
    return value;
  }

  async rebuildCatalog(sourceId: number, g: number): Promise<CatalogBlob> {
    const b = new CatalogIndexBuilder();
    const range = prefixRange([sourceId, g]);
    await this.ctx.db.scan<ChannelRecord>(S.channels, range, { index: 'pos' }, (r) => {
      b.channel(r.pos, normalizeForSearch(r.name), r.num);
      return true;
    });
    await this.ctx.db.scan<MovieRecord>(S.movies, range, { index: 'pos' }, (r) => {
      b.movie(r.pos, normalizeForSearch(r.name));
      return true;
    });
    await this.ctx.db.scan<SeriesRecord>(S.series, range, { index: 'pos' }, (r) => {
      b.seriesItem(r.pos, normalizeForSearch(r.name), r.genre, r.plot);
      return true;
    });
    const blob = b.build(g);
    await this.ctx.db.put(S.search, blob, [sourceId, 'catalog']);
    return blob;
  }

  async rebuildEpg(sourceId: number): Promise<EpgBlob> {
    const b = new EpgIndexBuilder();
    await this.ctx.db.scan<EpgBucket>(S.epg, prefixRange([sourceId]), {}, (bucket) => {
      for (const it of bucket.items) b.add(bucket.c, it.start, it.end, it.title, it.desc);
      return true;
    });
    const blob = b.build();
    await this.ctx.db.put(S.search, blob, [sourceId, 'epg']);
    return blob;
  }

  /**
   * For every token that matches nothing, finds the closest indexed word (same first letter,
   * Damerau-Levenshtein ≤ 1 for short words, ≤ 2 for longer ones; ties → most frequent word).
   * Returns null if nothing could be corrected.
   */
  async correct(sourceId: number, tokens: string[]): Promise<string[] | null> {
    try {
      const tables: Map<string, number>[] = [];
      const docsList: TextDocs[] = [];
      const cat = await this.catalog(sourceId);
      if (cat) {
        if (!cat.vocab) cat.vocab = cat.docs.vocabulary();
        tables.push(cat.vocab);
        docsList.push(cat.docs);
      }
      const epg = await this.epg(sourceId);
      if (epg) {
        if (!epg.vocab) {
          const weights = new Int32Array(epg.docs.count);
          const pd = epg.blob.progDoc;
          for (let i = 0; i < pd.length; i++) weights[pd[i]]++;
          epg.vocab = epg.docs.vocabulary(weights);
        }
        tables.push(epg.vocab);
        docsList.push(epg.docs);
      }
      if (tables.length === 0) return null;
      let changed = false;
      const corrected = tokens.map((token) => {
        if (token.length < 3 || /^\d+$/.test(token)) return token;
        const stem = QueryParser.stem(token);
        if (docsList.some((d) => d.hasPrefix(stem))) return token;
        const max = token.length <= 4 ? 1 : 2;
        const first = token.charAt(0);
        let best: string | null = null;
        let bestDist = Number.MAX_SAFE_INTEGER;
        let bestDocs = -1;
        for (const table of tables) {
          for (const [term, docs] of table) {
            if (term.charAt(0) !== first) continue;
            if (term.length < token.length - max || term.length > token.length + max) continue;
            const d = editDistance(token, term, max);
            // Ties: most frequent word, then alphabetical (FTS vocabularies are sorted).
            if (d <= max && (d < bestDist || (d === bestDist && (docs > bestDocs || (docs === bestDocs && best != null && term < best))))) {
              best = term; bestDist = d; bestDocs = docs;
            }
          }
        }
        if (best != null) { changed = true; return best; }
        return token;
      });
      return changed ? corrected : null;
    } catch (e) {
      logCore('typo correction failed', e);
      return null;
    }
  }
}

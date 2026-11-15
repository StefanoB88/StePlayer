// Port of search/SmartSearch.kt: global "search everything" across channels, movies, series
// (incl. plot/genre) and the TV guide — live now, upcoming and replayable (catch-up).
import type { Channel, EpgProgram, ProgramHit, ProgramStatus, SearchHit, SmartSearchResults } from '../api.ts';
import type { CatalogRepository } from '../data/catalogRepository.ts';
import type { Ctx } from '../data/context.ts';
import { S } from '../data/db.ts';
import type { EpgBucket } from '../data/epgBuckets.ts';
import { dayOf } from '../data/epgBuckets.ts';
import type { ChannelRecord, MovieRecord, SeriesRecord } from '../data/records.ts';
import { toChannel, toMovie, toProgram, toSeries } from '../data/records.ts';
import { logCore } from '../util/emitter.ts';
import { clamp, compareStrings, normalizeForSearch, stableSort } from '../util/text.ts';
import { DAY, HOUR, nowMillis } from '../util/time.ts';
import { QueryParser } from './queryParser.ts';
import type { SearchIndex } from './searchIndex.ts';

const KEY_RECENT = 'recent_searches';
const MAX_RECENT = 12;
const CATALOG_LIMIT = 1500;
const PROGRAM_LIMIT = 600;
const SECTION_LIMIT = 40;
const GRID_LIMIT = 120;
const TOP_LIMIT = 8;
const PLOT_SCORE = 18;
const DESCRIPTION_SCORE = 20;
const GUIDE_PAST = 7 * DAY;
const GUIDE_FUTURE = 7 * DAY;

type ChannelHit = Extract<SearchHit, { kind: 'channel' }>;
type MovieHit = Extract<SearchHit, { kind: 'movie' }>;
type SeriesHit = Extract<SearchHit, { kind: 'series' }>;

export function emptyResults(query: string): SmartSearchResults {
  return {
    query, correctedQuery: null, terms: [], topHits: [], liveNow: [], upcoming: [], replay: [], channels: [],
    movies: [], series: [],
  };
}

export function isEmptyResults(r: SmartSearchResults): boolean {
  return r.liveNow.length === 0 && r.upcoming.length === 0 && r.replay.length === 0 &&
    r.channels.length === 0 && r.movies.length === 0 && r.series.length === 0;
}

/** Relevance of a (normalised) title for the query. 0 = matched only elsewhere (plot, description). */
class Scorer {
  private readonly queryNorm: string;
  private readonly tokens: string[];
  private readonly stems: string[];

  constructor(queryNorm: string, tokens: string[]) {
    this.queryNorm = queryNorm;
    this.tokens = tokens;
    this.stems = tokens.map((t) => QueryParser.stem(t));
  }

  title(titleNorm: string): number {
    if (titleNorm === this.queryNorm) return 100;
    const words = QueryParser.tokenize(titleNorm);
    const joined = words.join(' ');
    if (joined === this.queryNorm) return 100;
    if (joined.startsWith(this.queryNorm)) return 85;
    if (this.tokens.every((t) => words.some((w) => w === t))) return 70;
    if (this.tokens.every((t) => words.some((w) => w.startsWith(t)))) return 55;
    if (this.stems.every((s) => words.some((w) => w.startsWith(s)))) return 40;
    return 0;
  }
}

function byScoreDesc<T extends { score: number }>(a: T, b: T): number {
  return b.score - a.score;
}

export class SmartSearch {
  private readonly ctx: Ctx;
  private readonly index: SearchIndex;
  private readonly catalog: CatalogRepository;

  constructor(ctx: Ctx, index: SearchIndex, catalog: CatalogRepository) {
    this.ctx = ctx;
    this.index = index;
    this.catalog = catalog;
  }

  // ------------------------------------------------------------------ recent queries

  async recentQueries(): Promise<string[]> {
    const v = await this.ctx.db.get<string>(S.settings, KEY_RECENT);
    return v == null ? [] : v.split('\n').filter((s) => s.trim() !== '');
  }

  async rememberQuery(query: string): Promise<void> {
    const clean = query.trim();
    if (clean.length < 2) return;
    const current = await this.recentQueries();
    const lower = clean.toLowerCase();
    const updated = [clean].concat(current.filter((q) => q.toLowerCase() !== lower)).slice(0, MAX_RECENT);
    await this.ctx.db.put(S.settings, updated.join('\n'), KEY_RECENT);
    this.ctx.events.emit('settings');
  }

  async clearRecentQueries(): Promise<void> {
    await this.ctx.db.delete(S.settings, KEY_RECENT);
    this.ctx.events.emit('settings');
  }

  // ------------------------------------------------------------------ search

  async search(sourceId: number, query: string): Promise<SmartSearchResults> {
    const tokens = QueryParser.meaningfulTokens(query);
    const isNumber = tokens.length === 1 && /^\d+$/.test(tokens[0]);
    const totalLength = tokens.reduce((n, t) => n + t.length, 0);
    if (tokens.length === 0 || (!isNumber && totalLength < 2)) return emptyResults(query);
    if ((await this.index.catalog(sourceId)) == null) return emptyResults(query);
    const now = nowMillis();
    const first = await this.run(sourceId, query, tokens, null, now);
    if (!isEmptyResults(first)) return first;
    const fixed = await this.index.correct(sourceId, tokens);
    if (fixed == null) return first;
    return this.run(sourceId, query, fixed, fixed.join(' '), now);
  }

  private async run(sourceId: number, query: string, tokens: string[], corrected: string | null, now: number): Promise<SmartSearchResults> {
    const stems = tokens.map((t) => QueryParser.stem(t));
    const queryNorm = tokens.join(' ');
    const scorer = new Scorer(queryNorm, tokens);
    const cat = await this.index.catalog(sourceId);
    if (!cat) return emptyResults(query);

    // ---- catalog
    const docs = cat.docs.match(stems, CATALOG_LIMIT);
    const [nCh, nMv] = cat.counts;
    const channelPos: number[] = [];
    const moviePos: number[] = [];
    const seriesPos: number[] = [];
    for (const d of docs) {
      if (d < nCh) channelPos.push(d);
      else if (d < nCh + nMv) moviePos.push(d - nCh);
      else seriesPos.push(d - nCh - nMv);
    }

    const channels = new Map<string, ChannelHit>();
    if (tokens.length === 1 && /^\d+$/.test(tokens[0])) {
      for (const ch of await this.catalog.channelsByNumber(sourceId, parseInt(tokens[0], 10))) {
        channels.set(ch.id, { kind: 'channel', channel: ch, score: 120 });
      }
    }
    const [chRows, mvRows, seRows] = await Promise.all([
      this.catalog.byPositions<ChannelRecord>(S.channels, sourceId, cat.g, channelPos),
      this.catalog.byPositions<MovieRecord>(S.movies, sourceId, cat.g, moviePos),
      this.catalog.byPositions<SeriesRecord>(S.series, sourceId, cat.g, seriesPos),
    ]);
    for (const row of chRows) {
      if (!channels.has(row.id)) {
        channels.set(row.id, { kind: 'channel', channel: toChannel(row), score: scorer.title(normalizeForSearch(row.name)) + 5 });
      }
    }
    const movies: MovieHit[] = mvRows.map((row) => {
      const bonus = Math.floor(clamp(row.rating ?? 0, 0, 10) / 2);
      return { kind: 'movie', movie: toMovie(row), score: scorer.title(normalizeForSearch(row.name)) + bonus };
    });
    const series: SeriesHit[] = seRows.map((row) => {
      const titleScore = scorer.title(normalizeForSearch(row.name));
      const bonus = Math.floor(clamp(row.rating ?? 0, 0, 10) / 2);
      return titleScore > 0
        ? { kind: 'series', series: toSeries(row), score: titleScore + bonus, matchedInPlot: false }
        : { kind: 'series', series: toSeries(row), score: PLOT_SCORE + bonus, matchedInPlot: true };
    });

    // ---- programmes (TV guide)
    let programs: ProgramHit[] = [];
    try {
      programs = await this.searchPrograms(sourceId, stems, scorer, now);
    } catch (e) {
      logCore('guide search failed', e);
    }

    const liveNow = stableSort(programs.filter((p) => p.status === 'LIVE'), byScoreDesc).slice(0, SECTION_LIMIT);
    const upcoming = stableSort(programs.filter((p) => p.status === 'UPCOMING'), (a, b) =>
      Number(b.score >= 50) - Number(a.score >= 50) || a.program.startAt - b.program.startAt).slice(0, SECTION_LIMIT);
    const replay = stableSort(programs.filter((p) => p.status === 'REPLAY'), (a, b) =>
      Number(b.score >= 50) - Number(a.score >= 50) || b.program.startAt - a.program.startAt).slice(0, SECTION_LIMIT);

    const channelList = stableSort(Array.from(channels.values()), byScoreDesc).slice(0, SECTION_LIMIT);
    const movieList = stableSort(movies, (a, b) => b.score - a.score || compareStrings(a.movie.name, b.movie.name)).slice(0, GRID_LIMIT);
    const seriesList = stableSort(series, (a, b) => b.score - a.score || compareStrings(a.series.name, b.series.name)).slice(0, GRID_LIMIT);

    const mixed: SearchHit[] = ([] as SearchHit[]).concat(
      liveNow, upcoming.slice(0, 10), replay.slice(0, 10), channelList, movieList, seriesList,
    );
    const top = stableSort(mixed, byScoreDesc).slice(0, TOP_LIMIT);

    return {
      query, correctedQuery: corrected, terms: stems, topHits: top, liveNow, upcoming, replay,
      channels: channelList, movies: movieList, series: seriesList,
    };
  }

  private async searchPrograms(sourceId: number, stems: string[], scorer: Scorer, now: number): Promise<ProgramHit[]> {
    const epg = await this.index.epg(sourceId);
    if (!epg) return [];
    const { blob, docs } = epg;
    const matched = docs.match(stems, Number.MAX_SAFE_INTEGER);
    if (matched.length === 0) return [];
    const docHit = new Uint8Array(docs.count);
    for (const d of matched) docHit[d] = 1;
    const from = now - GUIDE_PAST;
    const to = now + GUIDE_FUTURE;
    const hits: number[] = [];
    const { progDoc, progStart, progEnd } = blob;
    for (let i = 0; i < progDoc.length; i++) {
      if (docHit[progDoc[i]] === 1 && progEnd[i] > from && progStart[i] < to) hits.push(i);
    }
    // Live/upcoming first (chronological), then most recent past.
    hits.sort((a, b) => {
      const fa = progEnd[a] > now ? 0 : 1;
      const fb = progEnd[b] > now ? 0 : 1;
      if (fa !== fb) return fa - fb;
      const ka = fa === 0 ? progStart[a] : -progStart[a];
      const kb = fb === 0 ? progStart[b] : -progStart[b];
      return ka - kb || a - b;
    });
    // Same programme listed twice in the source file → one stored item.
    const wanted: [string, number][] = [];
    const seen = new Set<string>();
    for (const i of hits) {
      if (wanted.length >= PROGRAM_LIMIT) break;
      const epgId = blob.epgIds[blob.progEpg[i]];
      const k = epgId + '\n' + progStart[i];
      if (seen.has(k)) continue;
      seen.add(k);
      wanted.push([epgId, progStart[i]]);
    }
    // Hydrate from the per-channel-per-day buckets (one read per distinct bucket).
    const bucketKeys: [number, string, number][] = [];
    const bucketIndex = new Map<string, number>();
    for (const [epgId, start] of wanted) {
      const bk = epgId + '\n' + dayOf(start);
      if (!bucketIndex.has(bk)) { bucketIndex.set(bk, bucketKeys.length); bucketKeys.push([sourceId, epgId, dayOf(start)]); }
    }
    const buckets = await this.ctx.db.getMany<EpgBucket>(S.epg, bucketKeys);
    const programs: EpgProgram[] = [];
    for (const [epgId, start] of wanted) {
      const b = buckets[bucketIndex.get(epgId + '\n' + dayOf(start)) as number];
      const item = b ? b.items.filter((it) => it.start === start)[0] : undefined;
      if (item) programs.push(toProgram(epgId, item));
    }
    const epgIds: string[] = [];
    const idSeen = new Set<string>();
    for (const p of programs) if (!idSeen.has(p.channelEpgId)) { idSeen.add(p.channelEpgId); epgIds.push(p.channelEpgId); }
    const channelsByEpg = await this.catalog.channelsByEpgIds(sourceId, epgIds);

    interface Candidate { program: EpgProgram; channel: Channel; status: ProgramStatus; score: number }
    const candidates: Candidate[] = [];
    for (const p of programs) {
      const variants = channelsByEpg.get(p.channelEpgId);
      if (!variants) continue;
      let status: ProgramStatus;
      let channel: Channel;
      if (now >= p.startAt && now < p.endAt) {
        status = 'LIVE'; channel = variants[0];
      } else if (p.startAt >= now) {
        status = 'UPCOMING'; channel = variants[0];
      } else {
        // Past: only if some variant of the channel keeps an archive long enough.
        const v = variants.filter((c) => c.archiveDays > 0 && p.startAt >= now - c.archiveDays * DAY)[0];
        if (!v) continue;
        channel = v;
        status = 'REPLAY';
      }
      const titleScore = scorer.title(normalizeForSearch(p.title));
      const base = titleScore > 0 ? titleScore : DESCRIPTION_SCORE;
      let boost: number;
      if (status === 'LIVE') boost = 30;
      else if (status === 'UPCOMING') boost = p.startAt - now < 3 * HOUR ? 15 : p.startAt - now < DAY ? 8 : 2;
      else boost = 4;
      candidates.push({ program: p, channel, status, score: base + boost });
    }

    // Collapse repeated airings of the same show on the same channel (the same daily news show).
    const groups = new Map<string, Candidate[]>();
    for (const c of candidates) {
      const key = c.channel.id + '\n' + normalizeForSearch(c.program.title) + '\n' + c.status;
      const g = groups.get(key);
      if (g) g.push(c); else groups.set(key, [c]);
    }
    const out: ProgramHit[] = [];
    groups.forEach((group) => {
      let pick = group[0];
      for (const c of group) {
        if (group[0].status === 'REPLAY' ? c.program.startAt > pick.program.startAt : c.program.startAt < pick.program.startAt) pick = c;
      }
      out.push({ kind: 'program', program: pick.program, channel: pick.channel, status: pick.status, score: pick.score, otherAirings: group.length - 1 });
    });
    return out;
  }
}

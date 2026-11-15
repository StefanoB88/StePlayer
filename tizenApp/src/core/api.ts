// Contract between the core (data, sync, parsing: ported 1:1 from the Kotlin `shared` module) and the TV UI.
// Same names and semantics as Kotlin, adapted to the web: Promises instead of `suspend`, `subscribe` callbacks
// instead of Flows, IndexedDB instead of SQLDelight. No TS enums (string unions) so `node --test` runs the
// sources directly (type stripping).

export type SourceType = 'XTREAM' | 'M3U';
export type ContentType = 'LIVE' | 'MOVIE' | 'SERIES' | 'EPISODE';

export interface Source {
  id: number;
  name: string;
  type: SourceType;
  /** Xtream: server base URL. M3U: empty string (the list URL may carry tokens: kept with the secrets). */
  url: string;
  username: string | null;
  epgUrl: string | null;
  userAgent: string | null;
  serverTimeOffsetSec: number;
  allowedFormats: string[];
  expiresAt: number | null;
  maxConnections: number | null;
  lastSyncAt: number | null;
  lastEpgSyncAt: number | null;
}

/** What the user types in the "Aggiungi lista" form. */
export type SourceInput =
  | { kind: 'xtream'; name: string; serverUrl: string; username: string; password: string; userAgent?: string | null }
  | { kind: 'm3u'; name: string; listUrl: string; epgUrl?: string | null; userAgent?: string | null };

export interface Category { id: string; name: string; locked: boolean; hidden: boolean }

/** Virtual category ids (Kotlin `Categories`). */
export const ALL_CATEGORY = '__all__';
export const FAVORITES_CATEGORY = '__fav__';
export const RECENT_CATEGORY = '__recent__';

export interface Channel {
  sourceId: number; id: string; num: number | null; name: string; logo: string | null;
  categoryId: string | null; epgId: string | null; archiveDays: number;
}

export interface Movie {
  sourceId: number; id: string; name: string; poster: string | null; categoryId: string | null;
  rating: number | null; year: number | null; extension: string | null; addedAt: number | null;
}

export interface MovieDetail {
  movie: Movie; plot: string | null; genre: string | null; cast: string | null; director: string | null;
  releaseDate: string | null; durationSec: number | null; backdrop: string | null; trailerYoutubeId: string | null;
}

export interface Series {
  sourceId: number; id: string; name: string; cover: string | null; categoryId: string | null;
  plot: string | null; rating: number | null; year: number | null; genre: string | null;
}

export interface Episode {
  sourceId: number; seriesId: string; id: string; season: number; number: number; title: string;
  plot: string | null; image: string | null; durationSec: number | null; extension: string | null;
}

export interface SeriesDetail {
  series: Series; backdrop: string | null; cast: string | null; director: string | null;
  seasons: number[]; episodes: Episode[];
}

export interface EpgProgram { channelEpgId: string; startAt: number; endAt: number; title: string; description: string | null }

export interface ChannelWithEpg { channel: Channel; now: EpgProgram | null; next: EpgProgram | null }

export interface WatchProgress {
  sourceId: number; type: ContentType; itemId: string; parentId: string | null; title: string;
  subtitle: string | null; image: string | null; positionMs: number; durationMs: number; updatedAt: number;
}

export interface AccountInfo {
  status: string | null; expiresAt: number | null; maxConnections: number | null; activeConnections: number | null;
  isTrial: boolean; allowedFormats: string[];
}

/** A playable stream: URL plus the HTTP headers required to open it. */
export interface PlayableStream {
  url: string;
  headers: Record<string, string>;
  isLive: boolean;
  /** Container hint, e.g. "m3u8", "ts", "mp4", "mkv". */
  container: string | null;
}

export type SyncStep = 'AUTHENTICATING' | 'LIVE' | 'MOVIES' | 'SERIES' | 'EPG' | 'SAVING';
export type SyncState =
  | { kind: 'idle' }
  | { kind: 'running'; step: SyncStep; progress: number }
  | { kind: 'failed'; message: string }
  | { kind: 'done' };

// Smart search (Kotlin search/SearchModels.kt)
export type ProgramStatus = 'LIVE' | 'UPCOMING' | 'REPLAY';
export type SearchHit =
  | { kind: 'channel'; channel: Channel; score: number }
  | { kind: 'movie'; movie: Movie; score: number }
  | { kind: 'series'; series: Series; score: number; matchedInPlot: boolean }
  | { kind: 'program'; program: EpgProgram; channel: Channel; status: ProgramStatus; score: number; otherAirings: number };
export type ProgramHit = Extract<SearchHit, { kind: 'program' }>;

export interface SmartSearchResults {
  query: string;
  correctedQuery: string | null;
  terms: string[];
  topHits: SearchHit[];
  liveNow: ProgramHit[];
  upcoming: ProgramHit[];
  replay: ProgramHit[];
  channels: Extract<SearchHit, { kind: 'channel' }>[];
  movies: Extract<SearchHit, { kind: 'movie' }>[];
  series: Extract<SearchHit, { kind: 'series' }>[];
}

// Player settings (Kotlin settings/PlayerPreferences.kt) — same defaults.
export type BufferMode = 'AUTO' | 'NORMAL' | 'LARGE';
export type SubtitleMode = 'OFF' | 'AUTO' | 'ALWAYS';
export interface SubtitleStyle {
  scale: number;
  color: 'WHITE' | 'YELLOW';
  background: 'NONE' | 'SEMI_TRANSPARENT' | 'OPAQUE';
  edge: 'NONE' | 'OUTLINE' | 'SHADOW';
  bottomMarginPercent: number;
}
export interface PlayerSettings {
  stillWatchingAfterHours: number;
  bufferMode: BufferMode;
  preferredAudioLanguages: string[];
  preferredSubtitleLanguage: string | null;
  subtitleMode: SubtitleMode;
  subtitleStyle: SubtitleStyle;
  showStatsOverlay: boolean;
}

export interface BufferProfile {
  name: string; minBufferMs: number; maxBufferMs: number; bufferForPlaybackMs: number;
  bufferForPlaybackAfterRebufferMs: number; liveTargetOffsetMs: number;
}

export type PlaybackErrorKind =
  | 'MAX_CONNECTIONS' | 'ACCOUNT_EXPIRED' | 'UNAUTHORIZED' | 'NOT_FOUND' | 'SERVER' | 'NETWORK' | 'TIMEOUT'
  | 'STALLED' | 'UNSUPPORTED_FORMAT' | 'UNKNOWN';
export interface PlaybackError { kind: PlaybackErrorKind; title: string; message: string; retryable: boolean }

export interface RetryPolicy {
  /** Delay before the next automatic attempt, or null when the error must be shown (Kotlin RetryPolicy). */
  nextDelayMs(error: PlaybackError): number | null;
  reset(): void;
}

export interface StallWatchdog {
  /** @returns true if the stream must be considered stalled. */
  tick(nowMs: number, positionMs: number, wantsToPlay: boolean): boolean;
  reset(): void;
}

export interface BufferAdvisor {
  profile(): BufferProfile;
  /** Returns true if the profile just changed (restart the stream to apply). */
  onStall(nowMs: number): boolean;
}

/** In-app banner for a list that expires soon or has expired (Kotlin ExpiryNotice). */
export interface ExpiryNotice {
  sourceId: number; sourceName: string; expiresAtMs: number; daysLeft: number; expired: boolean; message: string;
}

export interface TrackInfo {
  id: string; language: string | null; label: string | null; channelCount: number; isDefault: boolean; isForced: boolean;
}

export type Unsubscribe = () => void;
export type CoreEvent = 'sources' | 'catalog' | 'epg' | 'library' | 'settings';

/** Channel zapping state (Kotlin zap/ChannelZapper.kt). */
export interface ChannelZapper {
  readonly current: Channel;
  readonly lastChannel: Channel | null;
  readonly pendingNumber: string;
  updateList(channels: Channel[]): void;
  next(): Channel;
  previous(): Channel;
  back(): Channel | null;
  select(channel: Channel): Channel;
  typeDigit(digit: number, nowMs: number): boolean;
  commitIfDue(nowMs: number, force?: boolean): number | null;
  channelForNumber(n: number): Channel | null;
  clearNumber(): void;
}

export interface Core {
  /** Fired after any change of that area (re-query what you show). */
  subscribe(event: CoreEvent, listener: () => void): Unsubscribe;

  sources: {
    current(): Promise<Source | null>;
    all(): Promise<Source[]>;
    byId(id: number): Promise<Source | null>;
    setActive(id: number): Promise<void>;
    /** Validates (Xtream login / M3U download) and stores; returns the new id. Throws an Error with an Italian message. */
    add(input: SourceInput): Promise<number>;
    update(id: number, input: SourceInput): Promise<void>;
    delete(id: number): Promise<void>;
    /** Values to pre-fill the "Modifica lista" form (secrets included, never logged). */
    inputFor(id: number): Promise<SourceInput | null>;
  };

  sync: {
    state(): SyncState;
    onState(listener: (s: SyncState) => void): Unsubscribe;
    sync(sourceId: number): Promise<void>;
    syncIfStale(sourceId: number, maxAgeMs?: number): Promise<void>;
  };

  catalog: {
    categories(sourceId: number, type: ContentType): Promise<Category[]>;
    allCategories(sourceId: number, type: ContentType): Promise<Category[]>;
    setCategoryHidden(sourceId: number, type: ContentType, category: Category, hidden: boolean): Promise<void>;
    /** categoryId may be ALL_CATEGORY / FAVORITES_CATEGORY / RECENT_CATEGORY. */
    channels(sourceId: number, categoryId: string): Promise<Channel[]>;
    channel(sourceId: number, id: string): Promise<Channel | null>;
    channelByNumber(sourceId: number, n: number): Promise<Channel | null>;
    movies(sourceId: number, categoryId: string, limit?: number, offset?: number): Promise<Movie[]>;
    movie(sourceId: number, id: string): Promise<Movie | null>;
    movieDetail(movie: Movie): Promise<MovieDetail>;
    series(sourceId: number, categoryId: string, limit?: number, offset?: number): Promise<Series[]>;
    seriesById(sourceId: number, id: string): Promise<Series | null>;
    seriesDetail(series: Series): Promise<SeriesDetail>;
    episode(sourceId: number, id: string): Promise<Episode | null>;
    /** Recently added movies (Home "Aggiunti di recente"). */
    recentMovies(sourceId: number, limit: number): Promise<Movie[]>;
  };

  epg: {
    refresh(source: Source): Promise<void>;
    nowNext(sourceId: number, channels: Channel[], now?: number): Promise<ChannelWithEpg[]>;
    programs(channel: Channel, from: number, to: number): Promise<EpgProgram[]>;
    guide(sourceId: number, epgIds: string[], from: number, to: number): Promise<Record<string, EpgProgram[]>>;
  };

  library: {
    isFavorite(sourceId: number, type: ContentType, itemId: string): Promise<boolean>;
    favoriteIds(sourceId: number, type: ContentType): Promise<Set<string>>;
    toggleFavorite(sourceId: number, type: ContentType, itemId: string): Promise<boolean>;
    saveProgress(p: Omit<WatchProgress, 'updatedAt'>): Promise<void>;
    progress(sourceId: number, type: ContentType, itemId: string): Promise<WatchProgress | null>;
    seriesProgress(sourceId: number, seriesId: string): Promise<Map<string, WatchProgress>>;
    continueWatching(sourceId: number, limit?: number): Promise<WatchProgress[]>;
    recentChannels(sourceId: number, limit?: number): Promise<WatchProgress[]>;
    removeProgress(sourceId: number, type: ContentType, itemId: string): Promise<void>;
  };

  search: {
    search(sourceId: number, query: string): Promise<SmartSearchResults>;
    recentQueries(): Promise<string[]>;
    rememberQuery(query: string): Promise<void>;
    clearRecentQueries(): Promise<void>;
  };

  streams: {
    live(channel: Channel): Promise<PlayableStream>;
    catchup(channel: Channel, program: EpgProgram): Promise<PlayableStream>;
    movie(movie: Movie): Promise<PlayableStream>;
    episode(episode: Episode): Promise<PlayableStream>;
  };

  playback: {
    retryPolicy(isLive: boolean): RetryPolicy;
    stallWatchdog(isLive: boolean): StallWatchdog;
    bufferAdvisor(mode: BufferMode): BufferAdvisor;
    errorForHttpStatus(sourceId: number, status: number): Promise<PlaybackError>;
    /** Asks the server (Xtream) what's wrong: expired, max connections... null when nothing specific. */
    diagnose(sourceId: number): Promise<PlaybackError | null>;
    errors: {
      network(): PlaybackError; timeout(): PlaybackError; stalled(): PlaybackError;
      unsupported(container: string | null): PlaybackError; unknown(detail?: string): PlaybackError;
    };
  };

  reminders: {
    /** Banner for the active list when it expires within 3 days (or expired), else null. */
    notice(sourceId: number, nowMs?: number): Promise<ExpiryNotice | null>;
    enabled(): Promise<boolean>;
    setEnabled(value: boolean): Promise<void>;
  };

  playerPreferences: {
    current(): Promise<PlayerSettings>;
    update(transform: (s: PlayerSettings) => PlayerSettings): Promise<void>;
    audioDelayMs(contentKey: string): Promise<number>;
    setAudioDelayMs(contentKey: string, ms: number): Promise<void>;
  };

  tracks: {
    /** Kotlin Tracks.pickAudio / pickSubtitle / displayName. */
    pickAudio(tracks: TrackInfo[], settings: PlayerSettings, deviceLanguages: string[]): TrackInfo | null;
    pickSubtitle(tracks: TrackInfo[], settings: PlayerSettings, deviceLanguages: string[], audioLanguage: string | null): TrackInfo | null;
    displayName(track: TrackInfo, index: number): string;
  };

  createZapper(channels: Channel[], current: Channel): ChannelZapper;
}

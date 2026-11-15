// Port of settings/PlayerPreferences.kt: player preferences + per-content A/V offsets (lip-sync
// problems are usually specific to one channel, so they're remembered per channel).
import type { BufferMode, PlayerSettings, SubtitleMode, SubtitleStyle } from '../api.ts';
import type { Ctx } from '../data/context.ts';
import { S } from '../data/db.ts';
import { clamp } from '../util/text.ts';

const KEY = 'player_settings';
const KEY_MIGRATED = 'player_settings_large_buffer_default';

export function defaultSubtitleStyle(): SubtitleStyle {
  return { scale: 1.0, color: 'WHITE', background: 'NONE', edge: 'OUTLINE', bottomMarginPercent: 8 };
}

export function defaultPlayerSettings(): PlayerSettings {
  return {
    stillWatchingAfterHours: 4,
    bufferMode: 'LARGE',
    preferredAudioLanguages: [],
    preferredSubtitleLanguage: null,
    subtitleMode: 'AUTO',
    subtitleStyle: defaultSubtitleStyle(),
    showStatsOverlay: false,
  };
}

/** Stable key for per-content offsets: "<sourceId>:<LIVE|MOVIE|EPISODE>:<id>". */
export function contentKey(sourceId: number, type: string, id: string): string {
  return `${sourceId}:${type}:${id}`;
}

function oneOf<T extends string>(v: unknown, values: readonly T[], fallback: T): T {
  return typeof v === 'string' && (values as readonly string[]).indexOf(v) >= 0 ? (v as T) : fallback;
}
function num(v: unknown, fallback: number): number {
  return typeof v === 'number' && isFinite(v) ? v : fallback;
}

/** Lenient decode (unknown keys ignored, invalid values → defaults), like kotlinx Json with defaults. */
export function decodeSettings(raw: string | null | undefined): PlayerSettings {
  const d = defaultPlayerSettings();
  if (raw == null) return d;
  let o: { [k: string]: unknown };
  try {
    const parsed = JSON.parse(raw);
    if (parsed == null || typeof parsed !== 'object') return d;
    o = parsed;
  } catch {
    return d;
  }
  const st = (o.subtitleStyle != null && typeof o.subtitleStyle === 'object' ? o.subtitleStyle : {}) as { [k: string]: unknown };
  const ds = d.subtitleStyle;
  return {
    stillWatchingAfterHours: Math.trunc(num(o.stillWatchingAfterHours, d.stillWatchingAfterHours)),
    bufferMode: oneOf<BufferMode>(o.bufferMode, ['AUTO', 'NORMAL', 'LARGE'], d.bufferMode),
    preferredAudioLanguages: Array.isArray(o.preferredAudioLanguages)
      ? o.preferredAudioLanguages.filter((x): x is string => typeof x === 'string') : [],
    preferredSubtitleLanguage: typeof o.preferredSubtitleLanguage === 'string' ? o.preferredSubtitleLanguage : null,
    subtitleMode: oneOf<SubtitleMode>(o.subtitleMode, ['OFF', 'AUTO', 'ALWAYS'], d.subtitleMode),
    subtitleStyle: {
      scale: num(st.scale, ds.scale),
      color: oneOf(st.color, ['WHITE', 'YELLOW'] as const, ds.color),
      background: oneOf(st.background, ['NONE', 'SEMI_TRANSPARENT', 'OPAQUE'] as const, ds.background),
      edge: oneOf(st.edge, ['NONE', 'OUTLINE', 'SHADOW'] as const, ds.edge),
      bottomMarginPercent: Math.trunc(num(st.bottomMarginPercent, ds.bottomMarginPercent)),
    },
    showStatsOverlay: typeof o.showStatsOverlay === 'boolean' ? o.showStatsOverlay : d.showStatsOverlay,
  };
}

export class PlayerPreferences {
  private readonly ctx: Ctx;

  constructor(ctx: Ctx) {
    this.ctx = ctx;
  }

  /**
   * One-time: settings saved before "Grande" became the default stored AUTO only because it was
   * the old default; move them to LARGE once. Later explicit choices are respected.
   */
  private async migrate(): Promise<void> {
    await this.ctx.db.run(S.settings, 'readwrite', (tx) => {
      const store = tx.objectStore(S.settings);
      const m = store.get(KEY_MIGRATED);
      m.onsuccess = () => {
        if (m.result != null) return;
        const r = store.get(KEY);
        r.onsuccess = () => {
          if (r.result != null) {
            const s = decodeSettings(r.result as string);
            if (s.bufferMode === 'AUTO') store.put(JSON.stringify({ ...s, bufferMode: 'LARGE' }), KEY);
          }
          store.put('1', KEY_MIGRATED);
        };
      };
      return () => undefined;
    });
  }

  async current(): Promise<PlayerSettings> {
    await this.migrate();
    return decodeSettings(await this.ctx.db.get<string>(S.settings, KEY));
  }

  async update(transform: (s: PlayerSettings) => PlayerSettings): Promise<void> {
    const updated = transform(await this.current());
    await this.ctx.db.put(S.settings, JSON.stringify(updated), KEY);
    this.ctx.events.emit('settings');
  }

  /** Audio delay in ms for a channel/movie/episode (positive = audio later). Range ±3000. */
  audioDelayMs(key: string): Promise<number> { return this.delay('audio', key); }
  setAudioDelayMs(key: string, ms: number): Promise<void> { return this.setDelay('audio', key, ms); }

  /** Subtitle delay in ms (positive = subtitles later). Range ±10000. */
  subtitleDelayMs(key: string): Promise<number> { return this.delay('sub', key); }
  setSubtitleDelayMs(key: string, ms: number): Promise<void> { return this.setDelay('sub', key, ms); }

  private async delay(kind: string, key: string): Promise<number> {
    const v = await this.ctx.db.get<string>(S.settings, `delay.${kind}.${key}`);
    return v != null && /^-?\d+$/.test(v) ? parseInt(v, 10) : 0;
  }

  private async setDelay(kind: string, key: string, ms: number): Promise<void> {
    const limit = kind === 'audio' ? 3_000 : 10_000;
    const v = clamp(Math.trunc(ms), -limit, limit);
    if (v === 0) await this.ctx.db.delete(S.settings, `delay.${kind}.${key}`);
    else await this.ctx.db.put(S.settings, String(v), `delay.${kind}.${key}`);
    this.ctx.events.emit('settings');
  }
}

// Rendering + transport abstraction (iOS PlayerEngine): Samsung AVPlay on the TV, <video> in a desktop browser.
import type { PlayableStream, BufferProfile } from '../../core/api.ts';

export interface EngineTrack { index: number; language: string | null; label: string | null; channels: number }

export interface EngineFailure {
  message: string;
  /** Container/codec the device can't decode. */
  unsupported?: boolean;
  /** Network-level failure (no answer, refused, timeout). */
  network?: boolean;
  timeout?: boolean;
}

export interface EngineCallbacks {
  onReady(): void;
  onEnded(): void;
  onError(f: EngineFailure): void;
  onBuffering(buffering: boolean): void;
  onSubtitle(text: string, durationMs: number): void;
}

export interface PlayerEngine {
  readonly name: string;
  open(stream: PlayableStream, startMs: number, profile: BufferProfile | null): void;
  play(): void;
  pause(): void;
  seek(ms: number): void;
  /** Full teardown: stop + close the connection. Idempotent. */
  stop(): void;
  readonly isPlaying: boolean;
  readonly isBuffering: boolean;
  readonly positionMs: number;
  readonly durationMs: number;
  audioTracks(): EngineTrack[];
  textTracks(): EngineTrack[];
  readonly selectedAudio: number;
  readonly selectedText: number | null;
  selectAudio(index: number): void;
  selectText(index: number | null): void;
  /** 0 = fit, 1 = fill, 2 = zoom */
  setAspect(mode: number): void;
  stats(): string[];
}

interface AvPlayApi { open(url: string): void }
export function avplay(): (AvPlayApi & Record<string, unknown>) | null {
  const w = window as unknown as { webapis?: { avplay?: AvPlayApi & Record<string, unknown> } };
  return w.webapis && w.webapis.avplay ? w.webapis.avplay : null;
}

// Playback on Vega (react-native-w3cmedia VideoPlayer): HLS through Amazon's hls.js (MSE mode), everything else
// (MP4, MKV, AVI, MPEG-TS) in URL mode, decoded by the system player. The video is drawn by <KeplerVideoSurfaceView>,
// whose surface handle is passed to `attachSurface`.
import { VideoPlayer } from '@amazon-devices/react-native-w3cmedia';
import { HlsJsPlayer } from '../vendor/hls/hlsjsplayer/HlsJsPlayer';
import { installHlsPolyfills } from './hlsPolyfills';

/* eslint-disable @typescript-eslint/no-explicit-any */
export type EngineEvent = 'playing' | 'pause' | 'waiting' | 'ended' | 'error' | 'timeupdate' | 'loadedmetadata';

export interface OpenRequest { url: string; isLive: boolean; container: string | null; startMs?: number }

export function isHls(r: OpenRequest): boolean {
  return /\.m3u8(\?|$)/i.test(r.url) || r.container === 'm3u8' || r.container === 'hls';
}

export class VegaEngine {
  readonly video: VideoPlayer = new VideoPlayer();
  private initialized: Promise<void> | null = null;
  private hls: HlsJsPlayer | null = null;
  private surface: unknown = null;
  private listeners = new Map<EngineEvent, Set<(e?: any) => void>>();
  private wired = false;
  /** Development: hls.js internal events. */
  hlsDebug: ((event: string, data: any) => void) | null = null;
  /** Whether the current stream goes through hls.js. */
  mode: 'hls' | 'url' | null = null;

  private ensureInit(): Promise<void> {
    if (!this.initialized) {
      this.initialized = (async () => {
        (globalThis as any).gmedia = this.video; // Amazon's document polyfill hands this to hls.js
        await this.video.initialize();
        this.wire();
      })();
    }
    return this.initialized;
  }

  private wire(): void {
    if (this.wired) return;
    this.wired = true;
    const events: EngineEvent[] = ['playing', 'pause', 'waiting', 'ended', 'error', 'timeupdate', 'loadedmetadata'];
    for (const ev of events) {
      this.video.addEventListener(ev, (e: any) => this.listeners.get(ev)?.forEach((f) => f(e)));
    }
  }

  on(ev: EngineEvent, f: (e?: any) => void): () => void {
    if (!this.listeners.has(ev)) this.listeners.set(ev, new Set());
    this.listeners.get(ev)!.add(f);
    return () => this.listeners.get(ev)?.delete(f);
  }

  attachSurface(handle: unknown): void {
    this.surface = handle;
    this.video.setSurfaceHandle(handle as string);
  }

  detachSurface(handle: unknown): void {
    if (this.surface === handle) this.surface = null;
    try { this.video.clearSurfaceHandle(handle as string); } catch { /* already gone */ }
  }

  async open(r: OpenRequest): Promise<void> {
    await this.ensureInit();
    await this.unload();
    this.video.autoplay = true;
    if (isHls(r)) {
      this.mode = 'hls';
      installHlsPolyfills();
      this.hls = new HlsJsPlayer(this.video as any);
      await this.hls.load({ secure: 'false', uri: r.url, drm_scheme: '', drm_license_uri: '' }, true);
      const lib = (this.hls as any).player;
      if (lib && this.hlsDebug) {
        for (const name of ['hlsFragLoading', 'hlsMediaAttached', 'hlsManifestParsed', 'hlsLevelLoaded', 'hlsFragLoaded', 'hlsBufferCreated', 'hlsBufferAppended', 'hlsError']) {
          lib.on(name, (_e: unknown, data: any) => this.hlsDebug?.(name, data));
        }
      }
    } else {
      this.mode = 'url';
      this.video.src = r.url;
    }
    if (r.startMs && r.startMs > 1000 && !r.isLive) {
      const seek = () => { this.video.currentTime = r.startMs! / 1000; off(); };
      const off = this.on('loadedmetadata', seek);
    }
    this.video.play();
  }

  play(): void { this.video.play(); }
  pause(): void { this.video.pause(); }
  seek(ms: number): void { this.video.currentTime = Math.max(0, ms / 1000); }
  get positionMs(): number { return (this.video.currentTime || 0) * 1000; }
  get durationMs(): number { const d = this.video.duration; return isFinite(d) && d > 0 ? d * 1000 : 0; }
  get isPaused(): boolean { return this.video.paused; }

  private async unload(): Promise<void> {
    if (this.hls) {
      try { await this.hls.destroy(); } catch { /* ignore */ }
      this.hls = null;
    }
    this.mode = null;
  }

  /** Full teardown (player screen closed / app to background): frees the decoder and the stream connection. */
  async release(): Promise<void> {
    await this.unload();
    try { this.video.pause(); } catch { /* ignore */ }
    if (this.initialized) {
      try { await this.video.deinitialize(); } catch { /* ignore */ }
      this.initialized = null;
      this.wired = false;
    }
  }
}

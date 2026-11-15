// <video> engine for development in a desktop browser (MP4 / what the browser can play).
import type { BufferProfile, PlayableStream } from '../../core/api.ts';
import type { EngineCallbacks, EngineTrack, PlayerEngine } from './engine.ts';

export class Html5Engine implements PlayerEngine {
  readonly name = 'HTML5';
  readonly video: HTMLVideoElement;
  private cb: EngineCallbacks;
  private buffering = false;
  private gen = 0;

  constructor(cb: EngineCallbacks, host: HTMLElement) {
    this.cb = cb;
    this.video = document.createElement('video');
    this.video.style.cssText = 'position:absolute;left:0;top:0;width:1920px;height:1080px;background:#000;object-fit:contain';
    this.video.setAttribute('playsinline', '');
    host.insertBefore(this.video, host.firstChild);
  }

  open(stream: PlayableStream, startMs: number, _profile: BufferProfile | null): void {
    this.stop();
    const g = ++this.gen;
    const v = this.video;
    this.buffering = true;
    v.onwaiting = () => { if (g === this.gen) { this.buffering = true; this.cb.onBuffering(true); } };
    v.onplaying = () => { if (g === this.gen) { this.buffering = false; this.cb.onBuffering(false); } };
    v.onended = () => { if (g === this.gen) this.cb.onEnded(); };
    v.onerror = () => {
      if (g !== this.gen) return;
      const code = v.error ? v.error.code : 0;
      this.stop();
      this.cb.onError({ message: `MEDIA_ERR_${code}`, unsupported: code === 4, network: code === 2 });
    };
    v.onloadedmetadata = () => {
      if (g !== this.gen) return;
      if (startMs > 1000 && !stream.isLive) v.currentTime = startMs / 1000;
      this.cb.onReady();
    };
    v.src = stream.url;
    const p = v.play();
    if (p && p.catch) p.catch(() => undefined);
  }

  play(): void { const p = this.video.play(); if (p && p.catch) p.catch(() => undefined); }
  pause(): void { this.video.pause(); }
  seek(ms: number): void { this.video.currentTime = Math.max(0, ms / 1000); }

  stop(): void {
    this.gen++;
    const v = this.video;
    v.onwaiting = v.onplaying = v.onended = v.onerror = v.onloadedmetadata = null;
    v.pause();
    v.removeAttribute('src');
    try { v.load(); } catch { /* ignore */ }
  }

  get isPlaying(): boolean { return !this.video.paused && !this.video.ended && this.video.readyState > 2; }
  get isBuffering(): boolean { return this.buffering && !this.video.paused; }
  get positionMs(): number { return (this.video.currentTime || 0) * 1000; }
  get durationMs(): number { const d = this.video.duration; return isFinite(d) && d > 0 ? d * 1000 : 0; }
  audioTracks(): EngineTrack[] { return []; }
  textTracks(): EngineTrack[] { return []; }
  get selectedAudio(): number { return -1; }
  get selectedText(): number | null { return null; }
  selectAudio(): void { /* not supported */ }
  selectText(): void { /* not supported */ }
  setAspect(mode: number): void { this.video.style.objectFit = mode === 1 ? 'fill' : mode === 2 ? 'cover' : 'contain'; }
  stats(): string[] {
    return ['Motore       HTML5 (sviluppo)', `Risoluzione  ${this.video.videoWidth}×${this.video.videoHeight}`];
  }
  destroy(): void { this.stop(); this.video.remove(); }
}

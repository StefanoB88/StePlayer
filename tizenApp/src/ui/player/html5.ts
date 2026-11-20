// <video> engine: LG webOS TVs (the system media pipeline decodes HLS, MPEG-TS, MP4, MKV in hardware) and desktop
// browsers during development. Audio tracks and subtitles use the standard audioTracks / textTracks lists when the
// TV exposes them; subtitles are drawn by the player screen, like with AVPlay.
import type { BufferProfile, PlayableStream } from '../../core/api.ts';
import type { EngineCallbacks, EngineTrack, PlayerEngine } from './engine.ts';
import { isWebOS } from '../platform.ts';

/* eslint-disable @typescript-eslint/no-explicit-any */
type TrackList = { length: number; [i: number]: any; addEventListener?: (t: string, f: () => void) => void };

/** No first frame within this time: the server is not answering (same as AVPlay's prepare timeout). */
const OPEN_TIMEOUT_MS = 20_000;

export class Html5Engine implements PlayerEngine {
  readonly name = isWebOS ? 'webOS' : 'HTML5';
  readonly video: HTMLVideoElement;
  private cb: EngineCallbacks;
  private buffering = false;
  private gen = 0;
  private openTimer = 0;
  private textIndex: number | null = null;
  private cueTrack: any = null;
  private onCue: (() => void) | null = null;

  constructor(cb: EngineCallbacks, host: HTMLElement) {
    this.cb = cb;
    this.video = document.createElement('video');
    this.video.style.cssText = 'position:absolute;left:0;top:0;width:1920px;height:1080px;background:#000;object-fit:contain';
    this.video.setAttribute('playsinline', '');
    this.video.setAttribute('preload', 'auto');
    host.insertBefore(this.video, host.firstChild);
  }

  open(stream: PlayableStream, startMs: number, _profile: BufferProfile | null): void {
    this.stop();
    const g = ++this.gen;
    const v = this.video;
    this.buffering = true;
    this.textIndex = null;
    let started = false;
    v.onwaiting = () => { if (g === this.gen) { this.buffering = true; this.cb.onBuffering(true); } };
    v.onplaying = () => {
      if (g !== this.gen) return;
      started = true;
      window.clearTimeout(this.openTimer);
      this.buffering = false;
      this.cb.onBuffering(false);
    };
    v.onended = () => { if (g === this.gen) this.cb.onEnded(); };
    v.onerror = () => {
      if (g !== this.gen) return;
      const code = v.error ? v.error.code : 0;
      if (code === 1) return; // aborted by us
      this.stop();
      this.cb.onError(failure(code, started));
    };
    v.onloadedmetadata = () => {
      if (g !== this.gen) return;
      if (startMs > 1000 && !stream.isLive) v.currentTime = startMs / 1000;
      this.cb.onReady();
    };
    this.openTimer = window.setTimeout(() => {
      if (g !== this.gen || started) return;
      this.stop();
      this.cb.onError({ message: 'Il server non risponde', network: true, timeout: true });
    }, OPEN_TIMEOUT_MS);
    v.src = stream.url;
    const p = v.play();
    if (p && p.catch) p.catch(() => undefined);
  }

  play(): void { const p = this.video.play(); if (p && p.catch) p.catch(() => undefined); }
  pause(): void { this.video.pause(); }
  seek(ms: number): void { this.video.currentTime = Math.max(0, ms / 1000); }

  stop(): void {
    this.gen++;
    window.clearTimeout(this.openTimer);
    this.detachCues();
    const v = this.video;
    v.onwaiting = v.onplaying = v.onended = v.onerror = v.onloadedmetadata = null;
    v.pause();
    v.removeAttribute('src');
    try { v.load(); } catch { /* ignore */ } // closes the connection
  }

  get isPlaying(): boolean { return !this.video.paused && !this.video.ended && this.video.readyState > 2; }
  get isBuffering(): boolean { return this.buffering && !this.video.paused; }
  get positionMs(): number { return (this.video.currentTime || 0) * 1000; }
  get durationMs(): number { const d = this.video.duration; return isFinite(d) && d > 0 ? d * 1000 : 0; }

  // ----- tracks

  private audioList(): TrackList | null { return (this.video as any).audioTracks || null; }

  /** Subtitle-like text tracks only (chapters / metadata are not for the user). */
  private textList(): any[] {
    const list = this.video.textTracks as unknown as TrackList | null;
    const out: any[] = [];
    if (!list) return out;
    for (let i = 0; i < list.length; i++) {
      const t = list[i];
      if (t && (t.kind === 'subtitles' || t.kind === 'captions')) out.push(t);
    }
    return out;
  }

  audioTracks(): EngineTrack[] {
    const list = this.audioList();
    const out: EngineTrack[] = [];
    if (!list) return out;
    for (let i = 0; i < list.length; i++) {
      const t = list[i];
      out.push({ index: i, language: t.language || null, label: t.label || null, channels: 0 });
    }
    return out;
  }

  textTracks(): EngineTrack[] {
    return this.textList().map((t, i) => ({ index: i, language: t.language || null, label: t.label || null, channels: 0 }));
  }

  get selectedAudio(): number {
    const list = this.audioList();
    if (!list) return -1;
    for (let i = 0; i < list.length; i++) if (list[i].enabled) return i;
    return -1;
  }

  get selectedText(): number | null { return this.textIndex; }

  selectAudio(index: number): void {
    const list = this.audioList();
    if (!list || index < 0 || index >= list.length) return;
    for (let i = 0; i < list.length; i++) list[i].enabled = i === index;
  }

  selectText(index: number | null): void {
    const tracks = this.textList();
    this.detachCues();
    this.textIndex = index != null && index >= 0 && index < tracks.length ? index : null;
    tracks.forEach((t, i) => { t.mode = i === this.textIndex ? 'hidden' : 'disabled'; }); // hidden = cues, no native drawing
    if (this.textIndex == null) { this.cb.onSubtitle('', 0); return; }
    const track = tracks[this.textIndex];
    const onCue = () => {
      const cues = track.activeCues as { length: number; [i: number]: { text?: string; endTime: number } } | null;
      if (!cues || !cues.length) { this.cb.onSubtitle('', 0); return; }
      const lines: string[] = [];
      let end = 0;
      for (let i = 0; i < cues.length; i++) { if (cues[i].text) lines.push(cues[i].text as string); end = Math.max(end, cues[i].endTime); }
      this.cb.onSubtitle(lines.join('\n'), Math.max(500, (end - this.video.currentTime) * 1000));
    };
    track.addEventListener('cuechange', onCue);
    this.cueTrack = track;
    this.onCue = onCue;
  }

  private detachCues(): void {
    if (this.cueTrack && this.onCue) this.cueTrack.removeEventListener('cuechange', this.onCue);
    this.cueTrack = null;
    this.onCue = null;
  }

  setAspect(mode: number): void { this.video.style.objectFit = mode === 1 ? 'fill' : mode === 2 ? 'cover' : 'contain'; }

  stats(): string[] {
    const v = this.video;
    const lines = [`Motore       ${isWebOS ? 'webOS (lettore di sistema)' : 'HTML5 (sviluppo)'}`];
    if (v.videoWidth) lines.push(`Risoluzione  ${v.videoWidth}×${v.videoHeight}`);
    const b = v.buffered;
    if (b && b.length) lines.push(`Buffer       ${Math.max(0, b.end(b.length - 1) - v.currentTime).toFixed(1)} s`);
    const q = (v as any).getVideoPlaybackQuality ? (v as any).getVideoPlaybackQuality() : null;
    if (q && q.totalVideoFrames) lines.push(`Fotogrammi   ${q.droppedVideoFrames || 0} persi su ${q.totalVideoFrames}`);
    return lines;
  }

  destroy(): void { this.stop(); this.video.remove(); }
}

function failure(code: number, started: boolean): { message: string; unsupported?: boolean; network?: boolean } {
  switch (code) {
    case 2: return { message: 'Connessione interrotta durante la riproduzione', network: true };
    case 3: return { message: 'Il televisore non riesce a decodificare questo video', unsupported: true };
    // Before the first frame "not supported" is also what a refused connection or an error page look like.
    case 4: return started ? { message: 'Formato non supportato dal televisore', unsupported: true }
      : { message: 'Impossibile aprire il video', network: true };
    default: return { message: 'Errore di riproduzione' };
  }
}

// Samsung AVPlay engine (hardware decoding: HLS, MPEG-TS, MP4, MKV, AVI...). The video plane sits behind the
// web page: the player screen makes the page transparent (body.video-mode).
import type { BufferProfile, PlayableStream } from '../../core/api.ts';
import type { EngineCallbacks, EngineTrack, PlayerEngine } from './engine.ts';

/* eslint-disable @typescript-eslint/no-explicit-any */
type Av = any;

function av(): Av { return (window as any).webapis.avplay; }

const DISPLAY = ['PLAYER_DISPLAY_MODE_LETTER_BOX', 'PLAYER_DISPLAY_MODE_FULL_SCREEN', 'PLAYER_DISPLAY_MODE_AUTO_ASPECT_RATIO'];

export class AvPlayEngine implements PlayerEngine {
  readonly name = 'AVPlay';
  private cb: EngineCallbacks;
  private buffering = false;
  private opened = false;
  private ready = false;
  private generation = 0;
  private audio = -1;
  private text: number | null = null;
  private aspect = 0;

  constructor(cb: EngineCallbacks) { this.cb = cb; }

  open(stream: PlayableStream, startMs: number, profile: BufferProfile | null): void {
    this.stop();
    const gen = ++this.generation;
    const p = av();
    this.buffering = true;
    this.ready = false;
    this.audio = -1;
    this.text = null;
    try {
      p.open(stream.url);
      this.opened = true;
      p.setListener({
        onbufferingstart: () => { if (gen === this.generation) { this.buffering = true; this.cb.onBuffering(true); } },
        onbufferingprogress: () => undefined,
        onbufferingcomplete: () => { if (gen === this.generation) { this.buffering = false; this.cb.onBuffering(false); } },
        oncurrentplaytime: () => undefined,
        onstreamcompleted: () => { if (gen === this.generation) this.cb.onEnded(); },
        onevent: () => undefined,
        onerror: (type: string) => { if (gen === this.generation) this.fail(type); },
        onsubtitlechange: (duration: number, text: string) => { if (gen === this.generation) this.cb.onSubtitle(String(text || ''), Number(duration) || 3000); },
        ondrmevent: () => undefined,
      });
      p.setDisplayRect(0, 0, 1920, 1080);
      try { p.setDisplayMethod(DISPLAY[this.aspect]); } catch { /* old firmware */ }
      const ua = stream.headers['User-Agent'];
      if (ua) { try { p.setStreamingProperty('USER_AGENT', ua); } catch { /* not supported */ } }
      if (profile) {
        const start = Math.max(1, Math.round(profile.bufferForPlaybackMs / 1000));
        const resume = Math.max(start, Math.round(profile.bufferForPlaybackAfterRebufferMs / 1000));
        try {
          p.setBufferingParam('PLAYER_BUFFER_FOR_PLAY', 'PLAYER_BUFFER_SIZE_IN_SECOND', start);
          p.setBufferingParam('PLAYER_BUFFER_FOR_RESUME', 'PLAYER_BUFFER_SIZE_IN_SECOND', resume);
        } catch { /* defaults */ }
      }
      p.prepareAsync(() => {
        if (gen !== this.generation) return;
        this.ready = true;
        const go = () => {
          if (gen !== this.generation) return;
          try { p.play(); } catch (e) { this.fail(String(e)); return; }
          this.buffering = false;
          this.cb.onBuffering(false);
          this.cb.onReady();
        };
        if (startMs > 1000 && !stream.isLive) p.seekTo(startMs, go, go); else go();
      }, (e: unknown) => { if (gen === this.generation) this.fail(errName(e)); });
    } catch (e) {
      this.fail(errName(e));
    }
  }

  private fail(type: string): void {
    const t = String(type || '');
    const unsupported = /NOT_SUPPORTED|UNSUPPORTED|INVALID_URI/.test(t);
    const network = /CONNECTION|NETWORK|TIMEOUT|STREAMING_PLAYER/.test(t);
    const timeout = /TIMEOUT/.test(t);
    this.stop();
    this.cb.onError({ message: t || 'Errore di riproduzione', unsupported, network, timeout });
  }

  play(): void { try { av().play(); } catch { /* not ready */ } }
  pause(): void { try { av().pause(); } catch { /* not ready */ } }

  seek(ms: number): void {
    try {
      this.buffering = true;
      this.cb.onBuffering(true);
      av().seekTo(Math.max(0, Math.round(ms)), () => { this.buffering = false; this.cb.onBuffering(false); }, () => { this.buffering = false; this.cb.onBuffering(false); });
    } catch { this.buffering = false; }
  }

  stop(): void {
    this.generation++;
    if (!this.opened) return;
    this.opened = false;
    this.ready = false;
    const p = av();
    try { p.stop(); } catch { /* already stopped */ }
    try { p.close(); } catch { /* already closed */ }
  }

  get isPlaying(): boolean {
    try { return this.opened && av().getState() === 'PLAYING'; } catch { return false; }
  }
  get isBuffering(): boolean { return this.opened && (this.buffering || !this.ready); }
  get positionMs(): number { try { return this.ready ? Number(av().getCurrentTime()) || 0 : 0; } catch { return 0; } }
  get durationMs(): number {
    try {
      const d = this.ready ? Number(av().getDuration()) || 0 : 0;
      return d > 0 && d < 24 * 3_600_000 ? d : 0;
    } catch { return 0; }
  }

  private tracks(type: 'AUDIO' | 'TEXT'): EngineTrack[] {
    if (!this.ready) return [];
    let info: any[] = [];
    try { info = av().getTotalTrackInfo() || []; } catch { return []; }
    const out: EngineTrack[] = [];
    for (const t of info) {
      if (t.type !== type) continue;
      let extra: any = {};
      try { extra = typeof t.extra_info === 'string' ? JSON.parse(t.extra_info) : t.extra_info || {}; } catch { /* plain */ }
      out.push({
        index: Number(t.index),
        language: extra.language || extra.track_lang || null,
        label: extra.track_name || null,
        channels: Number(extra.channels) || 0,
      });
    }
    return out;
  }
  audioTracks(): EngineTrack[] { return this.tracks('AUDIO'); }
  textTracks(): EngineTrack[] { return this.tracks('TEXT'); }

  get selectedAudio(): number {
    if (this.audio >= 0) return this.audio;
    try {
      const cur = av().getCurrentStreamInfo() || [];
      for (const s of cur) if (s.type === 'AUDIO') return Number(s.index);
    } catch { /* unknown */ }
    return -1;
  }
  get selectedText(): number | null { return this.text; }

  selectAudio(index: number): void { try { av().setSelectTrack('AUDIO', index); this.audio = index; } catch { /* ignore */ } }

  selectText(index: number | null): void {
    try {
      if (index == null) { av().setSilentSubtitle(true); this.text = null; return; }
      av().setSilentSubtitle(false);
      av().setSelectTrack('TEXT', index);
      this.text = index;
    } catch { /* ignore */ }
  }

  setAspect(mode: number): void {
    this.aspect = mode;
    try { av().setDisplayMethod(DISPLAY[mode] || DISPLAY[0]); } catch { /* ignore */ }
  }

  stats(): string[] {
    const lines: string[] = ['Motore       AVPlay (Samsung)'];
    try {
      const cur = av().getCurrentStreamInfo() || [];
      for (const s of cur) {
        let x: any = {};
        try { x = JSON.parse(s.extra_info || '{}'); } catch { /* none */ }
        if (s.type === 'VIDEO') {
          lines.push(`Risoluzione  ${x.Width || '?'}×${x.Height || '?'}${x.framerate ? ` @ ${x.framerate} fps` : ''}`);
          lines.push(`Video        ${x.fourCC || '?'}${x.Bit_rate ? `  ${Math.round(Number(x.Bit_rate) / 1000)} kbps` : ''}`);
        } else if (s.type === 'AUDIO') {
          lines.push(`Audio        ${x.fourCC || '?'}${x.channels ? `  ${x.channels} ch` : ''}`);
        }
      }
      lines.push(`Stato        ${av().getState()}`);
    } catch { /* unavailable */ }
    return lines;
  }
}

function errName(e: unknown): string {
  if (!e) return 'PLAYER_ERROR';
  const o = e as { name?: string; message?: string };
  return String(o.name || o.message || e);
}

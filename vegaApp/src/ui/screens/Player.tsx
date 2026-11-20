// Full-screen player (Samsung/LG/Android TV behaviour): controls on any key, hidden after 4 s; live: ▲/▼, CH+/CH−
// and number keys change channel; films/episodes: ◀/▶ ±10 s, ◀◀/▶▶ ±30 s, position saved and resumed. Errors retry
// automatically with the shared retry policy. The decoder and the stream connection are released in background.
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, StyleSheet, Text, View } from 'react-native';
import { KeplerVideoSurfaceView } from '@amazon-devices/react-native-w3cmedia';
import { useTVEventHandler, type HWEvent } from '@amazon-devices/react-native-kepler';
import type { Channel, ChannelZapper, EpgProgram, Episode, Movie, PlaybackError, Series } from '../../../../tizenApp/src/core/index.ts';
import { VegaEngine } from '../../player/engine';
import { Button, Focusable, ProgressBar } from '../components';
import { useNav } from '../nav';
import { useSession } from '../session';
import { C, F, S } from '../theme';
import { clock, duration } from '../format';
import { devLog } from '../../devlog';

export type PlayRequest =
  | { kind: 'live'; channel: Channel; list: Channel[] }
  | { kind: 'movie'; movie: Movie; startMs: number }
  | { kind: 'episode'; series: Series; episode: Episode; episodes: Episode[]; startMs: number };

export function PlayerScreen({ req }: { req: PlayRequest }) {
  const { core } = useSession();
  const nav = useNav();
  const engine = useRef<VegaEngine>(new VegaEngine());
  const surface = useRef<string | null>(null);
  const [current, setCurrent] = useState<PlayRequest>(req);
  const [controls, setControls] = useState(true);
  const [playing, setPlaying] = useState(false);
  const [buffering, setBuffering] = useState(true);
  const [pos, setPos] = useState(0);
  const [dur, setDur] = useState(0);
  const [error, setError] = useState<PlaybackError | null>(null);
  const [now, setNow] = useState<EpgProgram | null>(null);
  const [digits, setDigits] = useState('');
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // A stream that never starts (and never fails, e.g. refused by the platform) would leave "Caricamento…" forever:
  // after 25 s show the error with "Riprova", like the Samsung/LG app.
  const startTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const clearStartTimer = () => { if (startTimer.current) { clearTimeout(startTimer.current); startTimer.current = null; } };
  const zapper = useRef<ChannelZapper | null>(req.kind === 'live' ? core.createZapper(req.list, req.channel) : null);
  const retry = useRef(core.playback.retryPolicy(req.kind === 'live'));
  const token = useRef(0);
  const resumeAt = useRef<number>(req.kind === 'live' ? 0 : req.startMs);
  const lastSave = useRef(0);
  const isLive = current.kind === 'live';

  const title = current.kind === 'live' ? current.channel.name
    : current.kind === 'movie' ? current.movie.name
    : `${current.series.name} · S${current.episode.season} E${current.episode.number}`;

  const armHide = useCallback(() => {
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setControls(false), 4000);
  }, []);
  const show = useCallback(() => { setControls(true); armHide(); }, [armHide]);

  const saveProgress = useCallback((finished = false) => {
    const e = engine.current;
    const p = e.positionMs;
    const d = e.durationMs;
    if (current.kind === 'live' || (!finished && p < 5000)) return;
    if (current.kind === 'movie') {
      const m = current.movie;
      core.library.saveProgress({ sourceId: m.sourceId, type: 'MOVIE', itemId: m.id, parentId: null, title: m.name, subtitle: m.year ? String(m.year) : null, image: m.poster, positionMs: finished ? d : p, durationMs: d }).catch(() => undefined);
    } else {
      const ep = current.episode;
      core.library.saveProgress({ sourceId: ep.sourceId, type: 'EPISODE', itemId: ep.id, parentId: current.series.id, title: current.series.name, subtitle: `S${ep.season} E${ep.number} · ${ep.title}`, image: ep.image || current.series.cover, positionMs: finished ? d : p, durationMs: d }).catch(() => undefined);
    }
    lastSave.current = Date.now();
  }, [core, current]);

  /** Resolves the stream of `r` and opens it (after a channel change, a retry or coming back from background). */
  const start = useCallback(async (r: PlayRequest) => {
    const t = ++token.current;
    setError(null);
    setBuffering(true);
    try {
      const stream = r.kind === 'live' ? await core.streams.live(r.channel)
        : r.kind === 'movie' ? await core.streams.movie(r.movie) : await core.streams.episode(r.episode);
      if (t !== token.current) return;
      let startMs = resumeAt.current;
      if (!startMs && r.kind !== 'live') {
        const p = await core.library.progress(r.kind === 'movie' ? r.movie.sourceId : r.episode.sourceId, r.kind === 'movie' ? 'MOVIE' : 'EPISODE', r.kind === 'movie' ? r.movie.id : r.episode.id).catch(() => null);
        if (p && p.positionMs > 30_000 && !(p.durationMs > 0 && p.positionMs / p.durationMs >= 0.95)) startMs = p.positionMs;
      }
      if (surface.current) engine.current.attachSurface(surface.current);
      clearStartTimer();
      startTimer.current = setTimeout(() => {
        if (t !== token.current) return;
        setBuffering(false);
        setError({ ...core.playback.errors.timeout(), message: 'Il contenuto non parte. Controlla la connessione o riprova più tardi.' });
      }, 25_000);
      await engine.current.open({ url: stream.url, isLive: stream.isLive, container: stream.container, startMs });
      if (r.kind === 'live') {
        const c = r.channel;
        core.library.saveProgress({ sourceId: c.sourceId, type: 'LIVE', itemId: c.id, parentId: null, title: c.name, subtitle: null, image: c.logo, positionMs: 0, durationMs: 0 }).catch(() => undefined);
        core.epg.nowNext(c.sourceId, [c]).then((x) => { if (t === token.current) setNow(x[0] ? x[0].now : null); }).catch(() => undefined);
      }
    } catch (e) {
      if (t !== token.current) return;
      setError({ kind: 'UNKNOWN', title: 'Impossibile riprodurre il contenuto', message: (e as Error).message || 'Riprova tra qualche istante.', retryable: true } as PlaybackError);
    }
  }, [core]);

  // Engine events.
  useEffect(() => {
    const e = engine.current;
    const offs = [
      e.on('playing', () => { clearStartTimer(); setPlaying(true); setBuffering(false); retry.current.reset(); devLog(`player: playing (${e.mode})`); }),
      e.on('pause', () => setPlaying(false)),
      e.on('waiting', () => setBuffering(true)),
      e.on('timeupdate', () => {
        setPos(e.positionMs); setDur(e.durationMs);
        if (!isLive && Date.now() - lastSave.current > 10_000) saveProgress();
      }),
      e.on('ended', () => { saveProgress(true); if (!isLive) nav.pop(); }),
      e.on('error', async () => {
        clearStartTimer();
        const t = token.current;
        const err = core.playback.errors.network();
        const delay = retry.current.nextDelayMs(err);
        if (delay != null) { setBuffering(true); setTimeout(() => { if (t === token.current) start(current); }, delay); return; }
        const specific = await core.playback.diagnose(current.kind === 'live' ? current.channel.sourceId : current.kind === 'movie' ? current.movie.sourceId : current.episode.sourceId).catch(() => null);
        setError(specific || err);
      }),
    ];
    return () => offs.forEach((o) => o());
  }, [core, current, isLive, nav, saveProgress, start]);

  // Open on mount / channel change; release on close.
  useEffect(() => { start(current); }, [current, start]);
  useEffect(() => {
    armHide();
    const e = engine.current;
    return () => { saveProgress(); token.current++; clearStartTimer(); e.release(); if (hideTimer.current) clearTimeout(hideTimer.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Background: free the decoder and the provider connection; foreground: resume where we were.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'background') { if (!isLive) resumeAt.current = engine.current.positionMs; saveProgress(); token.current++; engine.current.release(); }
      else if (s === 'active') start(current);
    });
    return () => sub.remove();
  }, [current, isLive, saveProgress, start]);

  const zap = useCallback((ch: Channel | null) => {
    if (!ch || current.kind !== 'live') return;
    setNow(null);
    setCurrent({ kind: 'live', channel: ch, list: current.list });
    show();
  }, [current, show]);

  // Pending channel number: commit after a pause.
  useEffect(() => {
    if (!digits) return;
    const t = setInterval(() => {
      const z = zapper.current;
      if (!z) return;
      const n = z.commitIfDue(Date.now());
      if (n != null) { setDigits(''); zap(z.channelForNumber(n)); }
    }, 300);
    return () => clearInterval(t);
  }, [digits, zap]);

  const seekBy = (ms: number) => { const e = engine.current; e.seek(Math.max(0, Math.min(e.durationMs - 1000, e.positionMs + ms))); show(); };
  const toggle = () => { const e = engine.current; if (e.isPaused) e.play(); else e.pause(); show(); };

  useTVEventHandler((ev: HWEvent) => {
    if (ev.eventKeyAction === 1) return; // key release
    const k = ev.eventType;
    if (k === 'playpause') { toggle(); return; }
    if (k === 'pause') { engine.current.pause(); show(); return; }
    if (k === 'skip_forward' && !isLive) { seekBy(30_000); return; }
    if (k === 'skip_backward' && !isLive) { seekBy(-30_000); return; }
    if (isLive && (k === 'channel_up' || k === 'page_up')) { zap(zapper.current?.next() ?? null); return; }
    if (isLive && (k === 'channel_down' || k === 'page_down')) { zap(zapper.current?.previous() ?? null); return; }
    if (isLive && /^num_\d$/.test(k)) {
      const d = Number(k.slice(4));
      if (zapper.current?.typeDigit(d, Date.now())) setDigits(zapper.current.pendingNumber);
      return;
    }
    if (controls || error) { armHide(); return; } // the buttons have the focus
    if (k === 'select' || k === 'enter') { show(); return; }
    if (isLive && k === 'up') { zap(zapper.current?.next() ?? null); return; }
    if (isLive && k === 'down') { zap(zapper.current?.previous() ?? null); return; }
    if (!isLive && k === 'left') { seekBy(-10_000); return; }
    if (!isLive && k === 'right') { seekBy(10_000); return; }
    show();
  });

  const progressNow = now ? (Date.now() - now.startAt) / Math.max(1, now.endAt - now.startAt) : 0;

  return (
    <View style={styles.root}>
      <KeplerVideoSurfaceView
        style={styles.video}
        onSurfaceViewCreated={(h: string) => { surface.current = h; engine.current.attachSurface(h); }}
        onSurfaceViewDestroyed={(h: string) => { engine.current.detachSurface(h); surface.current = null; }}
      />
      {buffering && !error ? <View style={styles.center} pointerEvents="none"><Text style={styles.spinner}>Caricamento…</Text></View> : null}
      {digits ? <View style={styles.digits}><Text style={styles.digitsText}>{digits}</Text></View> : null}

      {error ? (
        <View style={styles.errorBox}>
          <Text style={F.headline}>{error.title}</Text>
          <Text style={[F.body2, { marginVertical: 10, textAlign: 'center' }]}>{error.message}</Text>
          <View style={{ flexDirection: 'row' }}>
            <Button label="Chiudi" onPress={() => nav.pop()} style={{ marginRight: 12 }} />
            {error.retryable ? <Button label="Riprova" primary preferred onPress={() => { retry.current.reset(); start(current); }} /> : null}
          </View>
        </View>
      ) : controls ? (
        <View style={StyleSheet.absoluteFill}>
          <View style={styles.top}>
            <Focusable onPress={() => nav.pop()} style={styles.back}>{() => <Text style={styles.backText}>←</Text>}</Focusable>
            <View style={{ flex: 1 }}>
              <Text style={F.headline} numberOfLines={1}>{title}</Text>
              {now ? <Text style={F.body2} numberOfLines={1}>{now.title}</Text> : null}
            </View>
          </View>
          <View style={styles.centerRow}>
            {!isLive ? <Button label="−10" onPress={() => seekBy(-10_000)} style={styles.round} /> : null}
            <Button label={playing ? 'Pausa' : 'Play'} primary preferred onPress={toggle} style={styles.play} />
            {!isLive ? <Button label="+10" onPress={() => seekBy(10_000)} style={styles.round} /> : null}
          </View>
          <View style={styles.bottom}>
            {isLive ? (
              now ? (
                <>
                  <View style={styles.rowBetween}>
                    <Text style={[F.title, { flex: 1, marginRight: 16 }]} numberOfLines={1}><Text style={styles.liveBadge}> LIVE </Text>  {now.title}</Text>
                    <Text style={F.body2}>{clock(now.startAt)} – {clock(now.endAt)}</Text>
                  </View>
                  <ProgressBar value={progressNow} style={{ marginTop: 8 }} />
                </>
              ) : <Text style={F.body2}>▲ ▼ per cambiare canale</Text>
            ) : (
              <>
                <ProgressBar value={dur > 0 ? pos / dur : 0} style={{ height: 6 }} />
                <View style={[styles.rowBetween, { marginTop: 6 }]}>
                  <Text style={F.label}>{duration(pos)}</Text>
                  <Text style={F.label}>{dur > 0 ? `-${duration(Math.max(0, dur - pos))}` : ''}</Text>
                </View>
              </>
            )}
          </View>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  video: { position: 'absolute', left: 0, top: 0, right: 0, bottom: 0, zIndex: 0 },
  center: { ...StyleSheet.absoluteFillObject, alignItems: 'center', justifyContent: 'center' },
  spinner: { color: C.gold, fontSize: 14, marginTop: 110 }, // below the Play button, never on top of it
  // Top and bottom bars ≥ 27 dp (5 %) from the screen edges: overscan-safe.
  top: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: S.margin, paddingTop: 28, paddingBottom: 30, backgroundColor: 'rgba(0,0,0,0.45)' },
  back: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginRight: 12, borderWidth: 2, borderColor: 'transparent' },
  backText: { color: C.text, fontSize: 22 },
  centerRow: { flex: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'center' },
  // minWidth, not width: with a fixed 64 dp the button padding left no room and "−10" broke onto three lines.
  round: { minWidth: 84, paddingHorizontal: 16, marginHorizontal: 16 },
  play: { minWidth: 120 },
  bottom: { paddingHorizontal: S.margin, paddingBottom: 28, paddingTop: 30, backgroundColor: 'rgba(0,0,0,0.45)' },
  rowBetween: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  liveBadge: { backgroundColor: C.live, color: '#fff', fontSize: 11, fontWeight: '800' },
  errorBox: { ...StyleSheet.absoluteFillObject, alignItems: 'center', justifyContent: 'center', backgroundColor: C.scrim, padding: 40 },
  digits: { position: 'absolute', right: S.margin, top: 28, backgroundColor: C.scrim, borderRadius: 10, paddingHorizontal: 16, paddingVertical: 8 },
  digitsText: { color: C.gold, fontSize: 30, fontWeight: '800' },
});

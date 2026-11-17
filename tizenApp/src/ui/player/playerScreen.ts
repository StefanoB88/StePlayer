// Player (Android player/PlayerController.kt + PlayerUi.kt + PlayerActivity key handling).
// Connection hygiene: every start, zap and retry tears the previous stream down first; leaving the app or the
// player always closes the stream.
import type {
  BufferAdvisor, Channel, EpgProgram, Episode, Movie, PlayableStream, PlaybackError, PlayerSettings, RetryPolicy, Series,
  StallWatchdog, SubtitleStyle, TrackInfo, ChannelZapper,
} from '../../core/api.ts';
import { ALL_CATEGORY } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, icon, clear, setText } from '../dom.ts';
import { ElementsZone, FocusScope, VirtualList, type Dir, type Zone } from '../focus.ts';
import { artImage, liveBadge, menuDialog, optionsDialog, primaryButton, progressBar, secondaryButton, setProgress, confirmDialog } from '../components.ts';
import { Format } from '../format.ts';
import type { KeyPress } from '../keys.ts';
import { prefs } from '../prefs.ts';
import { avplay, type EngineFailure, type PlayerEngine } from './engine.ts';
import { AvPlayEngine } from './avplay.ts';
import { Html5Engine } from './html5.ts';
import { WatchSessionTimer, type SessionState } from './watchSession.ts';
import { subtitleCss } from '../screens/settings.ts';
import { setScreenSaverAllowed } from '../tvsystem.ts';

export type PlayRequest =
  | { kind: 'live'; channel: Channel; list: Channel[] }
  | { kind: 'catchup'; channel: Channel; program: EpgProgram }
  | { kind: 'movie'; movie: Movie; startMs: number }
  | { kind: 'episode'; series: Series; episode: Episode; episodes: Episode[]; startMs: number };

const ASPECTS = ['Adatta', 'Riempi', 'Zoom'];


// Subtitle style choices in the player (same values as Impostazioni → Sottotitoli).
const SUB_SCALES = [0.75, 1, 1.25, 1.5, 2];
const SUB_COLORS: [string, SubtitleStyle['color']][] = [['Bianco', 'WHITE'], ['Giallo', 'YELLOW']];
const SUB_BACKGROUNDS: [string, SubtitleStyle['background']][] = [['Nessuno', 'NONE'], ['Semitrasparente', 'SEMI_TRANSPARENT'], ['Pieno', 'OPAQUE']];

export function playerScreen(initial: PlayRequest): Screen {
  const core = app().core;
  let req = initial;
  const isLive = () => req.kind === 'live';
  const sourceId = () => (req.kind === 'live' || req.kind === 'catchup' ? req.channel.sourceId : req.kind === 'movie' ? req.movie.sourceId : req.episode.sourceId);

  // ----- DOM
  const root = h('div', { class: 'screen player' });
  const subtitleEl = h('div', { class: 'p-subs hidden' });
  const spinner = h('div', { class: 'p-center hidden' }, h('div', { class: 'spinner' }));
  const controls = h('div', { class: 'p-controls hidden' });
  const banner = h('div', { class: 'p-zap hidden' });
  const numberEntry = h('div', { class: 'p-number hidden' });
  const drawer = h('div', { class: 'p-drawer hidden' });
  const overlay = h('div', { class: 'p-overlay hidden' });
  const stats = h('div', { class: 'p-stats hidden' });
  const notice = h('div', { class: 'p-notice hidden' });
  const nextPrompt = h('div', { class: 'p-next hidden' });
  const sleepPill = h('div', { class: 'p-sleep hidden' });
  root.append(subtitleEl, spinner, controls, banner, numberEntry, drawer, nextPrompt, sleepPill, stats, notice, overlay);

  // ----- engine
  const useAvPlay = !!avplay();
  const callbacks = {
    onReady: () => onReady(),
    onEnded: () => onEnded(),
    onError: (f: EngineFailure) => onEngineError(f),
    onBuffering: (b: boolean) => onBuffering(b),
    onSubtitle: (t: string, d: number) => showSubtitle(t, d),
  };
  const engine: PlayerEngine = useAvPlay ? new AvPlayEngine(callbacks) : new Html5Engine(callbacks, root);
  if (useAvPlay) document.body.classList.add('video-mode');

  // ----- state
  let settings: PlayerSettings | null = null;
  let retry: RetryPolicy = core.playback.retryPolicy(isLive());
  let watchdog: StallWatchdog = core.playback.stallWatchdog(isLive());
  let advisor: BufferAdvisor | null = null;
  let zapper: ChannelZapper | null = req.kind === 'live' ? core.createZapper(req.list, req.channel) : null;
  let nowProg: EpgProgram | null = null;
  let nextProg: EpgProgram | null = null;
  let controlsVisible = false;
  let drawerOpen = false;
  let userPaused = false;
  let starting = false;
  let buffering = true;
  let spinnerTimer = 0;
  let hideTimer = 0;
  let zapTimer = 0;
  let bannerTimer = 0;
  let digitTimer = 0;
  let retryTimer = 0;
  let subTimer = 0;
  let tickTimer = 0;
  let startToken = 0;
  let resumeAt: number | null = req.kind === 'movie' || req.kind === 'episode' ? req.startMs : null;
  let lastSave = 0;
  let failure: PlaybackError | null = null;
  let retrying: { seconds: number; error: PlaybackError } | null = null;
  let closed = false;
  let aspect = Number(prefs.get('aspect') || '0') || 0;
  let statsVisible = false;
  let nextCountdown: number | null = null;
  let nextDismissed = false;
  let sessionTimer: WatchSessionTimer | null = null;
  let sessionState: SessionState = { kind: 'running' };
  let stillDialog: Screen | null = null;
  let healthySince = 0;
  let scrubMs: number | null = null;
  let scrubTimer = 0;
  let scrubStep = 10_000;
  let lastScrubKey = 0;

  const scope = new FocusScope();

  // ----- titles
  function title(): string {
    switch (req.kind) {
      case 'live': return zapper ? zapper.current.name : req.channel.name;
      case 'catchup': return req.program.title;
      case 'movie': return req.movie.name;
      default: return req.series.name;
    }
  }
  function subtitle(): string | null {
    switch (req.kind) {
      case 'live': return nowProg ? nowProg.title : null;
      case 'catchup': return `${req.channel.name} · ${Format.dayTime(req.program.startAt)}`;
      case 'movie': return null;
      default: return `S${req.episode.season} E${req.episode.number} · ${req.episode.title}`;
    }
  }
  const currentChannel = (): Channel | null => (req.kind === 'live' ? (zapper ? zapper.current : req.channel) : req.kind === 'catchup' ? req.channel : null);
  const canSeek = () => !isLive() && engine.durationMs > 0;

  // ----- start / teardown
  async function resolve(): Promise<PlayableStream> {
    switch (req.kind) {
      case 'live': return core.streams.live(zapper ? zapper.current : req.channel);
      case 'catchup': return core.streams.catchup(req.channel, req.program);
      case 'movie': return core.streams.movie(req.movie);
      default: return core.streams.episode(req.episode);
    }
  }

  function teardown(): void {
    saveProgress();
    engine.stop();
    watchdog.reset();
    healthySince = 0;
  }

  async function start(): Promise<void> {
    if (closed) return;
    const token = ++startToken;
    window.clearTimeout(retryTimer);
    retrying = null;
    failure = null;
    teardown();
    starting = true;
    setBuffering(true);
    userPaused = false;
    nextCountdown = null;
    nextDismissed = false;
    renderOverlay();
    try {
      if (!settings) settings = await core.playerPreferences.current();
      if (!advisor) advisor = core.playback.bufferAdvisor(settings.bufferMode);
      if (!sessionTimer) {
        sessionTimer = new WatchSessionTimer(settings.stillWatchingAfterHours);
        sessionTimer.start(Date.now());
      }
      statsVisible = statsVisible || settings.showStatsOverlay;
      const stream = await resolve();
      if (token !== startToken || closed) return;
      let startMs = resumeAt != null ? resumeAt : 0;
      if (resumeAt == null && (req.kind === 'movie' || req.kind === 'episode')) startMs = await savedPosition();
      if (token !== startToken || closed) return;
      engine.setAspect(aspect);
      engine.open(stream, startMs, advisor.profile());
      starting = false;
      if (req.kind === 'live') {
        const c = zapper ? zapper.current : req.channel;
        core.library.saveProgress({ sourceId: c.sourceId, type: 'LIVE', itemId: c.id, parentId: null, title: c.name, subtitle: null, image: c.logo, positionMs: 0, durationMs: 0 }).catch(() => undefined);
        loadEpg(c);
      }
    } catch (e) {
      if (token !== startToken) return;
      starting = false;
      engine.stop();
      fail({ kind: 'UNKNOWN', title: 'Impossibile riprodurre il contenuto', message: errText(e), retryable: true });
    }
  }

  async function savedPosition(): Promise<number> {
    if (req.kind !== 'movie' && req.kind !== 'episode') return 0;
    const p = req.kind === 'movie'
      ? await core.library.progress(req.movie.sourceId, 'MOVIE', req.movie.id).catch(() => null)
      : await core.library.progress(req.episode.sourceId, 'EPISODE', req.episode.id).catch(() => null);
    if (!p || p.positionMs <= 30_000 || (p.durationMs > 0 && p.positionMs / p.durationMs >= 0.95)) return 0;
    return p.positionMs;
  }

  function saveProgress(finished = false): void {
    if (req.kind !== 'movie' && req.kind !== 'episode') return;
    const pos = engine.positionMs;
    const dur = engine.durationMs;
    if (!finished && pos < 5000) return;
    if (req.kind === 'movie') {
      const m = req.movie;
      core.library.saveProgress({ sourceId: m.sourceId, type: 'MOVIE', itemId: m.id, parentId: null, title: m.name, subtitle: m.year ? String(m.year) : null, image: m.poster, positionMs: finished ? dur : pos, durationMs: dur }).catch(() => undefined);
    } else {
      const e = req.episode;
      core.library.saveProgress({ sourceId: e.sourceId, type: 'EPISODE', itemId: e.id, parentId: req.series.id, title: req.series.name, subtitle: `S${e.season} E${e.number} · ${e.title}`, image: e.image || req.series.cover, positionMs: finished ? dur : pos, durationMs: dur }).catch(() => undefined);
    }
    lastSave = Date.now();
  }

  async function loadEpg(c: Channel): Promise<void> {
    try {
      const [r] = await core.epg.nowNext(c.sourceId, [c]);
      const cur = currentChannel();
      if (!cur || cur.id !== c.id) return;
      nowProg = r ? r.now : null;
      nextProg = r ? r.next : null;
      renderControls();
      renderBanner();
    } catch { /* no guide */ }
  }

  // ----- engine events
  function onReady(): void {
    setBuffering(false);
    autoSelectTracks();
    renderControls();
  }

  function onEnded(): void {
    if (req.kind === 'episode' && nextEpisode()) { playNextEpisode(); return; }
    if (req.kind === 'live') { onEngineError({ message: 'ended', network: true }); return; }
    saveProgress(true);
    close();
  }

  function onBuffering(b: boolean): void {
    if (b && !starting && advisor && advisor.onStall(Date.now())) {
      showNotice('Buffer aumentato per ridurre i blocchi');
    }
    setBuffering(b);
  }

  async function onEngineError(f: EngineFailure): Promise<void> {
    const pos = engine.positionMs;
    if (!isLive() && pos > 1000) resumeAt = pos;
    teardown();
    const token = startToken;
    let err: PlaybackError;
    if (f.unsupported) err = core.playback.errors.unsupported(null);
    else if (f.timeout) err = core.playback.errors.timeout();
    else {
      // Ambiguous: ask the server (API Xtream) what's wrong (expired, too many connections...).
      const d = await core.playback.diagnose(sourceId()).catch(() => null);
      err = d || (f.network ? core.playback.errors.network() : core.playback.errors.unknown());
    }
    if (token !== startToken || closed) return;
    fail(err);
  }

  function fail(err: PlaybackError): void {
    window.clearTimeout(retryTimer);
    const delay = retry.nextDelayMs(err);
    if (delay == null) {
      failure = err;
      retrying = null;
      setBuffering(false);
      renderOverlay();
      return;
    }
    retrying = { seconds: Math.ceil(delay / 1000), error: err };
    renderOverlay();
    const step = () => {
      if (!retrying || closed) return;
      retrying.seconds -= 1;
      if (retrying.seconds <= 0) { retrying = null; start(); return; }
      renderOverlay();
      retryTimer = window.setTimeout(step, 1000);
    };
    retryTimer = window.setTimeout(step, 1000);
  }

  // ----- tracks
  function deviceLanguages(): string[] {
    const nav = navigator as Navigator & { languages?: string[] };
    return (nav.languages && nav.languages.length ? nav.languages : [nav.language || 'it']).map((l) => l.split('-')[0]);
  }
  function asInfo(t: { index: number; language: string | null; label: string | null; channels: number }): TrackInfo {
    return { id: String(t.index), language: t.language, label: t.label, channelCount: t.channels, isDefault: false, isForced: false };
  }
  function autoSelectTracks(): void {
    if (!settings) return;
    const audio = engine.audioTracks().map(asInfo);
    const text = engine.textTracks().map(asInfo);
    const a = core.tracks.pickAudio(audio, settings, deviceLanguages());
    if (a) engine.selectAudio(Number(a.id));
    const s = core.tracks.pickSubtitle(text, settings, deviceLanguages(), a ? a.language : null);
    if (text.length) engine.selectText(s ? Number(s.id) : null);
  }

  function showSubtitle(text: string, durationMs: number): void {
    window.clearTimeout(subTimer);
    const clean = text.replace(/<[^>]+>/g, '').trim();
    if (!clean || engine.selectedText == null) { subtitleEl.classList.add('hidden'); return; }
    clear(subtitleEl);
    subtitleEl.appendChild(h('span', { style: settings ? subtitleCss(settings.subtitleStyle) : '' }, clean));
    if (settings) subtitleEl.style.bottom = `${Math.round(1080 * settings.subtitleStyle.bottomMarginPercent / 100)}px`;
    subtitleEl.classList.remove('hidden');
    subTimer = window.setTimeout(() => subtitleEl.classList.add('hidden'), Math.max(500, durationMs));
  }

  // ----- buffering spinner (only for loads longer than 300 ms)
  function setBuffering(b: boolean): void {
    buffering = b;
    window.clearTimeout(spinnerTimer);
    if (b) spinnerTimer = window.setTimeout(() => renderSpinner(), 300);
    else renderSpinner();
  }
  function renderSpinner(): void {
    const blocking = !!failure || !!retrying;
    const show = (buffering || starting) && !blocking;
    spinner.classList.toggle('hidden', !show || controlsVisible);
    if (controlsVisible) renderCenter();
  }

  // ----- controls
  let topZone: ElementsZone | null = null;
  let centerZone: ElementsZone | null = null;
  let seekZone: Zone | null = null;
  let playBtn: HTMLElement | null = null;
  let seekFill: HTMLElement | null = null;
  let seekThumb: HTMLElement | null = null;
  let timeLeft: HTMLElement | null = null;
  let timeRight: HTMLElement | null = null;
  let liveBar: HTMLElement | null = null;

  function showControls(): void {
    if (failure || retrying) return;
    const was = controlsVisible;
    controlsVisible = true;
    if (!was) {
      renderControls();
      controls.classList.remove('hidden');
      banner.classList.add('hidden');
      spinner.classList.add('hidden');
      focusDefault();
    }
    armHide();
  }
  function hideControls(): void {
    controlsVisible = false;
    controls.classList.add('hidden');
    window.clearTimeout(hideTimer);
    renderSpinner();
    updateScope();
  }
  function armHide(): void {
    window.clearTimeout(hideTimer);
    hideTimer = window.setTimeout(() => {
      if (engine.isPlaying && !app().hasDialog && scrubMs == null) hideControls(); else armHide();
    }, 4000);
  }

  function renderControls(): void {
    if (!controlsVisible) { updateScope(); return; }
    const focusedTop = topZone && scope.current === topZone ? topZone.current?.getAttribute('data-id') : null;
    clear(controls);
    const icn = (name: string, label: string, onClick: () => void, id: string) => {
      const b = h('div', { class: 'iconbtn s110', 'data-focus': true, 'data-id': id, title: label, style: 'color:#fff' }, icon(name, 48));
      b.addEventListener('click', () => { onClick(); armHide(); });
      return b;
    };
    const back = icn('arrow_back', 'Indietro', () => close(), 'back');
    const t = h('div', { class: 'col grow', style: 'margin-left:16px;min-width:0' },
      h('div', { class: 't-title-l ellipsis', style: 'color:#fff' }, title()),
      subtitle() ? h('div', { class: 't-body-m ellipsis', style: 'color:rgba(255,255,255,0.75)' }, subtitle() || '') : null);
    const top = h('div', { class: 'row p-top' }, back, t);
    if (isLive() && zapper && zapper.lastChannel) top.appendChild(icn('history', 'Ultimo canale', () => lastChannel(), 'last'));
    if (isLive() && req.kind === 'live' && req.list.length > 1) top.appendChild(icn('list', 'Canali', () => openDrawer(), 'list'));
    if (distinctTracks(engine.audioTracks(), engine.selectedAudio).length > 1) top.appendChild(icn('audiotrack', 'Audio', () => audioDialog(), 'audio'));
    // Always visible: style and track choice even when the stream has no subtitles (or they load late).
    top.appendChild(icn('closed_caption', 'Sottotitoli', () => textDialog(), 'text'));
    top.appendChild(icn('aspect_ratio', 'Formato', () => aspectDialog(), 'aspect'));
    top.appendChild(icn('tune', 'Opzioni', () => menu(), 'menu'));

    playBtn = h('div', { class: 'iconbtn s110', 'data-focus': true, 'data-default': true, style: 'width:152px;height:152px;color:#fff;background:rgba(255,255,255,0.16)' });
    playBtn.addEventListener('click', () => { if (!buffering) togglePlay(); armHide(); });
    const center = h('div', { class: 'row p-transport' });
    if (canSeek()) center.appendChild(seekBtn('replay_10', 'Indietro 10 secondi', -10_000));
    center.appendChild(playBtn);
    if (canSeek()) center.appendChild(seekBtn('forward_10', 'Avanti 10 secondi', 10_000));
    renderCenter();

    const bottom = h('div', { class: 'p-bottom' });
    if (isLive()) {
      const line = h('div', { class: 'row' }, liveBadge(),
        h('div', { class: 't-title-m ellipsis grow', style: 'color:#fff;margin-left:20px' }, nowProg ? nowProg.title : 'Nessuna informazione sul programma'),
        nowProg ? h('div', { class: 't-label-l', style: 'color:rgba(255,255,255,0.7)' }, `${Format.clock(nowProg.startAt)} – ${Format.clock(nowProg.endAt)}`) : null);
      bottom.appendChild(line);
      if (nowProg) {
        liveBar = progressBar(epgProgress(nowProg));
        liveBar.style.marginTop = '20px';
        bottom.appendChild(liveBar);
      }
      if (nextProg) bottom.appendChild(h('div', { class: 't-body-m ellipsis', style: 'margin-top:16px;color:rgba(255,255,255,0.7)' }, `Dopo: ${Format.clock(nextProg.startAt)}  ${nextProg.title}`));
      seekZone = null;
    } else {
      seekFill = h('div', { class: 'p-seek-fill' });
      seekThumb = h('div', { class: 'p-seek-thumb' });
      const track = h('div', { class: 'p-seek s100', 'data-focus': true }, h('div', { class: 'p-seek-track' }, seekFill), seekThumb);
      timeLeft = h('div', { class: 't-label-l', style: 'color:#fff' });
      timeRight = h('div', { class: 't-label-l', style: 'color:rgba(255,255,255,0.7)' });
      bottom.append(track, h('div', { class: 'row', style: 'justify-content:space-between;margin-top:8px' }, timeLeft, timeRight));
      seekZone = seekBarZone(track);
      updateSeek();
    }
    controls.append(h('div', { class: 'p-scrim-top' }), h('div', { class: 'p-scrim-bottom' }), top, center, bottom);
    topZone = new ElementsZone(top, { only: 'x' });
    centerZone = new ElementsZone(center, { only: 'x' });
    updateScope();
    if (focusedTop && topZone) {
      const e = top.querySelector(`[data-id="${focusedTop}"]`) as HTMLElement | null;
      if (e) { scope.focus(topZone); topZone.focusElement(e); }
    }
  }

  function seekBtn(name: string, label: string, delta: number): HTMLElement {
    const b = h('div', { class: 'iconbtn s110', 'data-focus': true, title: label, style: 'width:112px;height:112px;color:#fff' }, icon(name, 64));
    b.addEventListener('click', () => { seekBy(delta); armHide(); });
    return b;
  }

  function renderCenter(): void {
    if (!playBtn) return;
    clear(playBtn);
    const loading = (buffering || starting) && !failure && !retrying;
    if (loading) {
      playBtn.style.background = 'transparent';
      playBtn.appendChild(h('div', { class: 'spinner' }));
    } else {
      playBtn.style.background = 'rgba(255,255,255,0.16)';
      playBtn.appendChild(icon(engine.isPlaying ? 'pause' : 'play_arrow', 84));
    }
  }

  function epgProgress(p: EpgProgram): number {
    return (Date.now() - p.startAt) / Math.max(1, p.endAt - p.startAt);
  }

  function updateSeek(): void {
    if (!seekFill || !seekThumb || !timeLeft || !timeRight) return;
    const dur = engine.durationMs;
    const pos = scrubMs != null ? scrubMs : engine.positionMs;
    const f = dur > 0 ? Math.max(0, Math.min(1, pos / dur)) : 0;
    seekFill.style.width = `${f * 100}%`;
    seekThumb.style.left = `${f * 100}%`;
    setText(timeLeft, Format.duration(pos));
    setText(timeRight, dur > 0 ? `-${Format.duration(Math.max(0, dur - pos))}` : '');
  }

  /** Seek bar: left/right move a preview position (step grows while held), OK or a pause commits. */
  function seekBarZone(el: HTMLElement): Zone {
    const z = new ElementsZone(el.parentElement as HTMLElement, { only: 'x' });
    const custom: Zone = {
      el,
      canFocus: () => canSeek(),
      enter: (f, d) => z.enter(f, d),
      leave: () => z.leave(),
      focusedRect: () => el.getBoundingClientRect(),
      activate: () => commitScrub(),
      move: (dir: Dir) => {
        if (dir === 'up' || dir === 'down') { commitScrub(); return false; }
        const now = Date.now();
        scrubStep = now - lastScrubKey < 400 ? Math.min(120_000, scrubStep * 1.5) : 10_000;
        lastScrubKey = now;
        const base = scrubMs != null ? scrubMs : engine.positionMs;
        scrubMs = Math.max(0, Math.min(engine.durationMs - 1000, base + (dir === 'left' ? -scrubStep : scrubStep)));
        updateSeek();
        armHide();
        window.clearTimeout(scrubTimer);
        scrubTimer = window.setTimeout(() => commitScrub(), 900);
        return true;
      },
    };
    return custom;
  }

  function commitScrub(): void {
    window.clearTimeout(scrubTimer);
    if (scrubMs == null) return;
    const target = scrubMs;
    scrubMs = null;
    engine.seek(target);
    watchdog.reset();
    updateSeek();
  }

  function updateScope(): void {
    const zones: Zone[] = [];
    if (failure || retrying) zones.push(overlayZone!);
    else if (stillDialog) { /* dialog has its own scope */ }
    else if (drawerOpen) zones.push(drawerList);
    else {
      if (nextCountdown != null && nextZone) zones.push(nextZone);
      if (sleepZone && sessionState.kind === 'sleep') zones.push(sleepZone);
      if (controlsVisible) {
        if (topZone) zones.push(topZone);
        if (centerZone) zones.push(centerZone);
        if (seekZone) zones.push(seekZone);
      }
    }
    scope.zones = zones.filter(Boolean);
    if (!scope.current || scope.zones.indexOf(scope.current) < 0) {
      scope.current = null;
      if (scope.zones.length) focusDefault();
    }
  }

  function focusDefault(): void {
    updateScopeZonesOnly();
    if (failure || retrying) { scope.focus(overlayZone); return; }
    if (drawerOpen) { scope.focus(drawerList); return; }
    if (nextCountdown != null && nextZone) { scope.focus(nextZone); return; }
    if (controlsVisible && centerZone) scope.focus(centerZone);
  }
  function updateScopeZonesOnly(): void {
    const zones: Zone[] = [];
    if (failure || retrying) { if (overlayZone) zones.push(overlayZone); }
    else if (drawerOpen) zones.push(drawerList);
    else {
      if (nextCountdown != null && nextZone) zones.push(nextZone);
      if (sleepZone && sessionState.kind === 'sleep') zones.push(sleepZone);
      if (controlsVisible) { if (topZone) zones.push(topZone); if (centerZone) zones.push(centerZone); if (seekZone) zones.push(seekZone); }
    }
    scope.zones = zones;
  }

  // ----- dialogs
  /** Engine tracks reduced to one per language (shared rules); the one in use always stays. */
  function distinctTracks<T extends { index: number; language: string | null; label: string | null; channels: number }>(all: T[], sel: number | null): T[] {
    const keep = core.tracks.distinct(all.map(asInfo), sel != null ? String(sel) : null).map((t) => t.id);
    return all.filter((t) => keep.indexOf(String(t.index)) >= 0);
  }
  function audioDialog(): void {
    const sel = engine.selectedAudio;
    const tracks = distinctTracks(engine.audioTracks(), sel);
    optionsDialog('Audio', tracks.map((t, i) => ({ label: core.tracks.displayName(asInfo(t), i), checked: t.index === sel })), (i) => engine.selectAudio(tracks[i].index));
  }
  /** Subtitles menu: track (when the stream has some) and style, applied live and saved as the default. */
  function textDialog(): void {
    const sel = engine.selectedText;
    const tracks = distinctTracks(engine.textTracks(), sel);
    const st = settings ? settings.subtitleStyle : null;
    const current = tracks.find((t) => t.index === sel);
    const entries: [string, () => void][] = [
      tracks.length
        ? [`Lingua sottotitoli: ${current ? core.tracks.displayName(asInfo(current), tracks.indexOf(current)) : 'Disattivati'}`, () => trackDialog()]
        : ['Nessun sottotitolo in questo contenuto', () => textDialog()],
      [`Dimensione: ${Math.round((st ? st.scale : 1) * 100)}%`, () => choiceDialog('Dimensione', SUB_SCALES.map((v) => [`${Math.round(v * 100)}%`, v] as [string, number]), st ? st.scale : 1, (v) => setStyle({ scale: v }))],
      [`Colore: ${SUB_COLORS.find((c) => c[1] === (st ? st.color : 'WHITE'))![0]}`, () => choiceDialog('Colore', SUB_COLORS, st ? st.color : 'WHITE', (v) => setStyle({ color: v }))],
      [`Sfondo del testo: ${SUB_BACKGROUNDS.find((b) => b[1] === (st ? st.background : 'NONE'))![0]}`, () => choiceDialog('Sfondo del testo', SUB_BACKGROUNDS, st ? st.background : 'NONE', (v) => setStyle({ background: v }))],
    ];
    menuDialog('Sottotitoli', entries.map((e) => e[0]), (i) => entries[i][1]());
  }
  function trackDialog(): void {
    const sel = engine.selectedText;
    const tracks = distinctTracks(engine.textTracks(), sel);
    optionsDialog('Sottotitoli', [{ label: 'Disattivati', checked: sel == null }, ...tracks.map((t, i) => ({ label: core.tracks.displayName(asInfo(t), i), checked: t.index === sel }))],
      (i) => { engine.selectText(i === 0 ? null : tracks[i - 1].index); if (i === 0) subtitleEl.classList.add('hidden'); textDialog(); });
  }
  function choiceDialog<T>(title: string, options: [string, T][], selected: T, apply: (v: T) => void): void {
    optionsDialog(title, options.map(([label, v]) => ({ label, checked: v === selected })), (i) => { apply(options[i][1]); textDialog(); });
  }
  function setStyle(change: Partial<SubtitleStyle>): void {
    if (settings) settings = { ...settings, subtitleStyle: { ...settings.subtitleStyle, ...change } };
    core.playerPreferences.update((s) => ({ ...s, subtitleStyle: { ...s.subtitleStyle, ...change } })).catch(() => undefined);
  }
  function aspectDialog(): void {
    optionsDialog('Formato', ASPECTS.map((l, i) => ({ label: l, checked: aspect === i })), (i) => {
      aspect = i; prefs.set('aspect', String(i)); engine.setAspect(i);
    });
  }
  function menu(): void {
    const entries: [string, () => void][] = [
      [sleepLabel(), () => sleepDialog()],
      [`Statistiche: ${statsVisible ? 'ON' : 'OFF'}`, () => { statsVisible = !statsVisible; renderStats(); }],
      ['Perché si blocca?', () => whyStalls()],
    ];
    menuDialog('Opzioni', entries.map((e) => e[0]), (i) => entries[i][1]());
  }
  function sleepLabel(): string {
    const rem = sessionTimer ? sessionTimer.sleepRemainingMs(Date.now()) : null;
    return rem != null ? `Spegnimento alle ${Format.clock(Date.now() + rem)}` : 'Timer di spegnimento';
  }
  function sleepDialog(): void {
    const values = [0, 15, 30, 60, 90];
    if (isLive() && nowProg) values.push(-1);
    const active = sessionTimer && sessionTimer.sleepRemainingMs(Date.now()) != null;
    optionsDialog(sleepLabel(), values.map((v) => ({ label: v === 0 ? 'Disattivato' : v === -1 ? 'Fine programma' : `${v} min`, checked: v === 0 && !active })), (i) => {
      const v = values[i];
      if (!sessionTimer) return;
      if (v === 0) sessionTimer.cancelSleep();
      else if (v === -1 && nowProg) sessionTimer.setSleepAt(nowProg.endAt);
      else sessionTimer.setSleepTimer(v, Date.now());
    });
  }
  async function whyStalls(): Promise<void> {
    const d = await core.playback.diagnose(sourceId()).catch(() => null);
    const base = 'I blocchi dipendono quasi sempre dalla rete o dal server del provider. Prova il buffer Grande nelle impostazioni, il cavo di rete al posto del Wi-Fi o un altro orario. Se succede con tutti i canali, il problema è del provider.';
    confirmDialog('Perché si blocca?', d ? `${d.title}: ${d.message}\n\n${base}` : base, 'OK', () => undefined, 'Chiudi');
  }

  // ----- overlays: retry / error
  let overlayZone: Zone | null = null;
  function renderOverlay(): void {
    clear(overlay);
    if (retrying) {
      const cancel = secondaryButton('Annulla', () => {
        window.clearTimeout(retryTimer);
        const e = retrying ? retrying.error : core.playback.errors.network();
        retrying = null;
        failure = e;
        renderOverlay();
      });
      cancel.setAttribute('data-default', '');
      overlay.appendChild(h('div', { class: 'col', style: 'width:920px;align-items:center;text-align:center' },
        h('div', { class: 'spinner', style: 'width:80px;height:80px' }),
        h('div', { class: 't-title-m', style: 'color:#fff;margin-top:32px' }, retrying.error.title),
        h('div', { class: 't-body-m c2', style: 'margin-top:8px' }, retrying.error.message),
        h('div', { class: 't-label-l', style: 'color:var(--accent2);margin:20px 0 36px' }, `Riprovo tra ${retrying.seconds} s…`),
        cancel));
      overlay.style.background = 'rgba(0,0,0,0.6)';
    } else if (failure) {
      const retryBtn = primaryButton('Riprova', () => { retry.reset(); start(); });
      retryBtn.setAttribute('data-default', '');
      const backBtn = secondaryButton('Indietro', () => close());
      const why = secondaryButton('Perché si blocca?', () => whyStalls());
      overlay.appendChild(h('div', { class: 'col', style: 'width:920px;align-items:center;text-align:center' },
        h('span', { style: 'color:var(--live)' }, icon('wifi_off', 104)),
        h('div', { class: 't-title-l', style: 'color:#fff;margin-top:28px' }, failure.title),
        h('div', { class: 't-body-m c2', style: 'margin-top:12px' }, failure.message),
        h('div', { class: 'btns', style: 'margin-top:44px;justify-content:center' }, retryBtn, backBtn),
        why));
      overlay.style.background = 'rgba(0,0,0,0.8)';
    }
    const show = !!retrying || !!failure;
    overlay.classList.toggle('hidden', !show);
    if (show) { controls.classList.add('hidden'); controlsVisible = false; spinner.classList.add('hidden'); }
    overlayZone = show ? new ElementsZone(overlay) : null;
    updateScope();
    if (show) scope.focus(overlayZone);
    renderSpinner();
  }

  // ----- live: zap, numbers, banner, drawer
  function zap(delta: number): void {
    if (!zapper || req.kind !== 'live') return;
    apply(delta > 0 ? zapper.next() : zapper.previous());
  }
  function lastChannel(): void {
    if (!zapper) return;
    const c = zapper.back();
    if (c) apply(c, true);
  }
  function apply(c: Channel, selected = false): void {
    if (req.kind !== 'live' || !zapper) return;
    if (!selected) zapper.select(c);
    nowProg = null;
    nextProg = null;
    retry.reset();
    resumeAt = null;
    window.clearTimeout(retryTimer);
    retrying = null;
    failure = null;
    renderOverlay();
    if (controlsVisible) renderControls();
    showBanner();
    loadEpg(c);
    window.clearTimeout(zapTimer);
    // The overlay updates instantly; the stream opens 300 ms after the last zap (fast single zaps, and a burst of
    // CH presses still opens only the final channel: one provider connection).
    zapTimer = window.setTimeout(() => start(), 300);
  }
  function showBanner(): void {
    renderBanner();
    if (controlsVisible || drawerOpen) return;
    banner.classList.remove('hidden');
    window.clearTimeout(bannerTimer);
    bannerTimer = window.setTimeout(() => banner.classList.add('hidden'), 4000);
  }
  function renderBanner(): void {
    const c = currentChannel();
    if (!c || !isLive()) return;
    clear(banner);
    const info = h('div', { class: 'col grow', style: 'min-width:0' },
      h('div', { class: 'row' }, c.num != null ? h('span', { class: 't-title-m', style: 'color:var(--accent2);margin-right:16px' }, String(c.num)) : null,
        h('span', { class: 't-title-m ellipsis' }, c.name)),
      h('div', { class: 't-body-m c2 ellipsis' }, nowProg ? nowProg.title : 'Nessuna informazione sul programma'));
    if (nowProg) { const b = progressBar(epgProgress(nowProg), 'thin'); b.style.marginTop = '12px'; info.appendChild(b); }
    if (nextProg) info.appendChild(h('div', { class: 't-body-s c2 ellipsis', style: 'margin-top:12px' }, `Dopo: ${Format.clock(nextProg.startAt)}  ${nextProg.title}`));
    const logo = h('div', { style: 'position:relative;width:160px;height:104px;border-radius:20px;overflow:hidden;margin-right:28px;flex:0 0 auto' }, artImage(c.logo, c.name, { fit: true }));
    (logo.firstChild as HTMLElement).style.cssText += ';position:absolute;left:0;top:0;width:100%;height:100%';
    banner.append(logo, info, h('div', { style: 'margin-left:20px' }, liveBadge()));
  }

  function typeDigit(d: number): void {
    if (!zapper) return;
    zapper.typeDigit(d, Date.now());
    renderNumber();
    window.clearTimeout(digitTimer);
    digitTimer = window.setTimeout(() => commitNumber(), 1600);
  }
  async function commitNumber(): Promise<void> {
    if (!zapper || req.kind !== 'live') return;
    const n = zapper.commitIfDue(Date.now(), true);
    renderNumber();
    if (n == null) return;
    const target = zapper.channelForNumber(n) || await core.catalog.channelByNumber(sourceId(), n).catch(() => null);
    if (!target || req.kind !== 'live' || !zapper) { app().toast(`Canale ${n} non trovato`); return; }
    if (!req.list.some((c) => c.id === target.id)) {
      // Different category: switch the zapping list to the target's category (Android commitNumber).
      let list: Channel[] = [];
      try { list = await core.catalog.channels(target.sourceId, target.categoryId || ALL_CATEGORY); } catch { /* single */ }
      if (!list.some((c) => c.id === target.id)) list = [target];
      req = { kind: 'live', channel: target, list };
      zapper = core.createZapper(list, target);
      drawerEpg = new Map();
    }
    apply(target);
  }
  function renderNumber(): void {
    const digits = zapper ? zapper.pendingNumber : '';
    numberEntry.classList.toggle('hidden', !digits);
    numberEntry.textContent = (digits.split('').join(' ') + (digits.length < 4 ? ' _' : '')).trim();
  }

  const drawerList = new VirtualList<Channel>('p-drawer-list', {
    rowHeight: 116, gap: 6, padTop: 8, padBottom: 24,
    render: (c) => {
      const cur = currentChannel();
      const logo = h('div', { style: 'position:relative;width:112px;height:76px;border-radius:16px;overflow:hidden;margin-right:20px;flex:0 0 auto' }, artImage(c.logo, c.name, { fit: true }));
      (logo.firstChild as HTMLElement).style.cssText += ';position:absolute;left:0;top:0;width:100%;height:100%';
      const now = drawerEpg.get(c.id) || null;
      const text = h('div', { class: 'col grow', style: 'min-width:0' },
        h('div', { class: 't-title-s ellipsis' }, [c.num != null ? String(c.num) : null, c.name].filter(Boolean).join('  ')));
      if (now) {
        text.appendChild(h('div', { class: 't-body-s c2 ellipsis' }, now.title));
        const bar = progressBar(epgProgress(now), 'hair');
        bar.style.marginTop = '8px';
        text.appendChild(bar);
      }
      return h('div', { class: `row s102${cur && cur.id === c.id ? ' current' : ''}`, style: 'margin:0 20px;padding:16px;border-radius:24px' }, logo, text);
    },
    onActivate: (c) => { closeDrawer(); const cur = currentChannel(); if (!cur || cur.id !== c.id) apply(c); },
  });
  drawer.append(h('div', { class: 't-title-l', style: 'padding:32px 40px 16px' }, 'Canali'), drawerList.el);
  drawerList.el.style.cssText = 'position:absolute;left:0;right:0;top:112px;bottom:0;overflow:hidden';

  let drawerEpg = new Map<string, EpgProgram>();
  async function loadDrawerEpg(list: Channel[]): Promise<void> {
    try {
      const rows = await core.epg.nowNext(sourceId(), list);
      drawerEpg = new Map();
      for (const r of rows) if (r.now) drawerEpg.set(r.channel.id, r.now);
      if (drawerOpen) drawerList.refresh();
    } catch { /* no guide */ }
  }
  function openDrawer(): void {
    if (req.kind !== 'live') return;
    if (!drawerEpg.size) loadDrawerEpg(req.list);
    hideControls();
    drawerOpen = true;
    drawer.classList.remove('hidden');
    banner.classList.add('hidden');
    const cur = currentChannel();
    drawerList.setItems(req.list);
    const idx = Math.max(0, req.list.findIndex((c) => cur && c.id === cur.id));
    drawerList.index = idx;
    drawerList.scrollToIndex(idx, false);
    drawerList.refresh();
    updateScope();
    scope.focus(drawerList);
  }
  function closeDrawer(): void {
    drawerOpen = false;
    drawer.classList.add('hidden');
    updateScope();
  }

  // ----- next episode
  let nextZone: ElementsZone | null = null;
  function nextEpisode(): Episode | null {
    if (req.kind !== 'episode') return null;
    const all = req.episodes;
    const i = all.findIndex((e) => req.kind === 'episode' && e.id === req.episode.id);
    return i >= 0 && i + 1 < all.length ? all[i + 1] : null;
  }
  function renderNext(): void {
    const ep = nextEpisode();
    clear(nextPrompt);
    if (nextCountdown == null || !ep) { nextPrompt.classList.add('hidden'); nextZone = null; updateScope(); return; }
    const playNow = primaryButton('Riproduci ora', () => playNextEpisode(), 'skip_next');
    playNow.setAttribute('data-default', '');
    const cancel = secondaryButton('Annulla', () => { nextDismissed = true; nextCountdown = null; renderNext(); });
    const btns = h('div', { class: 'btns', style: 'margin-top:24px' }, playNow, cancel);
    nextPrompt.append(
      h('div', { class: 't-label-l', style: 'color:var(--accent2)' }, `Prossimo episodio tra ${nextCountdown} s`),
      h('div', { class: 't-title-m clamp2', style: 'margin-top:8px' }, `S${ep.season} E${ep.number} · ${ep.title}`), btns);
    nextPrompt.classList.remove('hidden');
    const had = !!nextZone && scope.current === nextZone;
    nextZone = new ElementsZone(btns, { only: 'x' });
    updateScope();
    if (!had) scope.focus(nextZone);
    else scope.focus(nextZone);
  }
  function playNextEpisode(): void {
    const ep = nextEpisode();
    if (!ep || req.kind !== 'episode') return;
    saveProgress(true);
    req = { ...req, episode: ep, startMs: 0 };
    resumeAt = null;
    nextCountdown = null;
    renderNext();
    retry.reset();
    start();
    if (controlsVisible) renderControls();
  }

  // ----- session: still watching / sleep
  let sleepZone: ElementsZone | null = null;
  function handleSession(): void {
    if (!sessionTimer) return;
    const st = sessionTimer.tick(Date.now());
    const prevKind = sessionState.kind;
    sessionState = st;
    if (st.kind === 'stop') {
      closeStillDialog();
      teardown();
      app().toast(st.reason === 'INACTIVITY' ? 'Riproduzione interrotta per inattività' : 'Timer di spegnimento terminato', 5000);
      close();
      return;
    }
    if (st.kind === 'ask') {
      if (!stillDialog) {
        stillDialog = confirmDialog('Stai ancora guardando?', '', 'Continua a guardare', () => { stillDialog = null; interaction(); }, 'Esci');
        // "Esci" = cancel button of the dialog: close the player too.
        const cancelBtn = stillDialog.el.querySelector('.btn.secondary') as HTMLElement | null;
        if (cancelBtn) cancelBtn.addEventListener('click', () => { stillDialog = null; close(); });
      }
      const body = stillDialog.el.querySelector('.dtitle') as HTMLElement | null;
      if (body) body.setAttribute('data-sub', '');
      const msg = `La riproduzione si fermerà tra ${st.secondsLeft} s per non tenere occupata la connessione.`;
      let m = stillDialog.el.querySelector('.still-msg') as HTMLElement | null;
      if (!m) { m = h('div', { class: 'still-msg t-body-l c2', style: 'margin-bottom:16px' }); const t = stillDialog.el.querySelector('.dtitle'); if (t && t.parentElement) t.parentElement.insertBefore(m, t.nextSibling); }
      m.textContent = msg;
    } else if (prevKind === 'ask') {
      closeStillDialog();
    }
    if (st.kind === 'sleep') {
      clear(sleepPill);
      const plus = secondaryButton('+15 min', () => { sessionTimer?.extendSleep(15, Date.now()); handleSession(); });
      const cancel = secondaryButton('Annulla', () => { sessionTimer?.cancelSleep(); handleSession(); });
      const btns = h('div', { class: 'row' }, plus, h('div', { style: 'width:20px' }), cancel);
      sleepPill.append(h('div', { class: 't-title-s', style: 'margin-right:28px' }, `Spegnimento tra ${st.secondsLeft} s`), btns);
      sleepPill.classList.remove('hidden');
      if (!sleepZone) { sleepZone = new ElementsZone(btns, { only: 'x' }); updateScope(); scope.focus(sleepZone); }
      else { const had = scope.current === sleepZone; sleepZone = new ElementsZone(btns, { only: 'x' }); updateScope(); if (had) scope.focus(sleepZone); }
    } else if (sleepZone) {
      sleepPill.classList.add('hidden');
      sleepZone = null;
      updateScope();
    }
  }
  function closeStillDialog(): void {
    if (stillDialog) { app().closeDialog(stillDialog); stillDialog = null; }
  }
  function interaction(): void {
    sessionTimer?.onInteraction(Date.now());
  }

  // ----- misc
  function togglePlay(): void {
    if (engine.isPlaying) { engine.pause(); userPaused = true; }
    else { engine.play(); userPaused = false; watchdog.reset(); }
    window.setTimeout(() => renderCenter(), 150);
  }
  function seekBy(delta: number): void {
    if (!canSeek()) return;
    const target = Math.max(0, Math.min(engine.durationMs - 1000, engine.positionMs + delta));
    engine.seek(target);
    watchdog.reset();
    window.setTimeout(updateSeek, 100);
  }
  function showNotice(text: string): void {
    notice.textContent = text;
    notice.classList.remove('hidden');
    window.setTimeout(() => notice.classList.add('hidden'), 5000);
  }
  function renderStats(): void {
    stats.classList.toggle('hidden', !statsVisible);
    if (!statsVisible) return;
    const p = advisor ? advisor.profile() : null;
    const lines = [...engine.stats(), `Buffer       ${p ? p.name : '—'}`];
    stats.textContent = lines.join('\n');
  }

  function tick(): void {
    if (closed) return;
    const now = Date.now();
    setScreenSaverAllowed(!engine.isPlaying);
    if (controlsVisible) {
      updateSeek();
      if (liveBar && nowProg) setProgress(liveBar, epgProgress(nowProg));
      renderCenter();
    }
    if (statsVisible) renderStats();
    const streaming = !starting && !failure && !retrying;
    if (streaming) {
      const wants = !userPaused;
      if (watchdog.tick(now, Math.round(engine.positionMs), wants)) {
        const pos = engine.positionMs;
        if (!isLive() && pos > 1000) resumeAt = pos;
        teardown();
        fail(core.playback.errors.stalled());
        return;
      }
      if (engine.isPlaying) {
        if (!healthySince) healthySince = now;
        else if (healthySince > 0 && now - healthySince > 30_000) { retry.reset(); healthySince = -1; }
      } else if (healthySince > 0) healthySince = 0;
    }
    if (!isLive() && now - lastSave > 10_000 && engine.positionMs > 5000) saveProgress();
    if (!isLive() && engine.positionMs > 1000) resumeAt = engine.positionMs;
    // Next episode countdown in the last 25 s.
    if (req.kind === 'episode' && !nextDismissed && nextEpisode()) {
      const dur = engine.durationMs;
      const left = dur - engine.positionMs;
      if (dur > 120_000 && left < 25_000 && engine.isPlaying) {
        const s = Math.max(0, Math.ceil(left / 1000));
        if (nextCountdown !== s) { nextCountdown = s; renderNext(); }
      }
    }
    if (isLive() && nowProg && nowProg.endAt < now) { const c = currentChannel(); if (c) loadEpg(c); }
    if (engine.isPlaying || sessionState.kind !== 'running') handleSession();
  }

  function close(): void {
    if (closed) return;
    closed = true;
    startToken++;
    [hideTimer, zapTimer, bannerTimer, digitTimer, retryTimer, subTimer, spinnerTimer, scrubTimer].forEach((t) => window.clearTimeout(t));
    window.clearInterval(tickTimer);
    closeStillDialog();
    teardown();
    document.removeEventListener('visibilitychange', onVisibility);
    document.body.classList.remove('video-mode');
    setScreenSaverAllowed(true);
    if (engine instanceof Html5Engine) engine.destroy();
    app().remove(screen);
  }

  // App to the background: free the connection; back in the foreground: restart where we were.
  function onVisibility(): void {
    if (closed) return;
    if (document.hidden) {
      const pos = engine.positionMs;
      if (!isLive() && pos > 1000) resumeAt = pos;
      startToken++;
      window.clearTimeout(retryTimer);
      teardown();
    } else if (!failure) {
      start();
    }
  }
  document.addEventListener('visibilitychange', onVisibility);

  const screen: Screen = {
    el: root,
    scope,
    onKey: (k: KeyPress) => {
      interaction();
      if (stillDialog) return false;
      if (controlsVisible) armHide();
      // Shortcuts that work in any state.
      if (k.key === 'digit' && isLive()) { typeDigit(k.digit); return true; }
      switch (k.key) {
        case 'chUp': if (isLive()) zap(1); return true;
        case 'chDown': if (isLive()) zap(-1); return true;
        case 'info': if (isLive()) { if (controlsVisible) hideControls(); else showControls(); } else showControls(); return true;
        case 'playPause': case 'play': case 'pause':
          if (!failure && !retrying) {
            // MediaPlay only resumes, MediaPause only pauses; PlayPause toggles.
            if (k.key === 'playPause' || (k.key === 'play') !== engine.isPlaying) togglePlay();
            if (!isLive()) showControls();
          }
          return true;
        case 'stop': close(); return true;
        case 'ff': if (canSeek()) { seekBy(30_000); showControls(); } return true;
        case 'rw': if (canSeek()) { seekBy(-30_000); showControls(); } return true;
        default: break;
      }
      if (controlsVisible || drawerOpen || failure || retrying || nextCountdown != null || sessionState.kind === 'sleep') return false;
      switch (k.key) {
        case 'enter': showControls(); return true;
        case 'up': if (isLive()) zap(1); else showControls(); return true;
        case 'down': if (isLive()) zap(-1); else showControls(); return true;
        case 'left': if (isLive()) openDrawer(); else { seekBy(-10_000); showControls(); } return true;
        case 'right': if (isLive()) showControls(); else { seekBy(10_000); showControls(); } return true;
        default: return false;
      }
    },
    onBack: () => {
      if (drawerOpen) { closeDrawer(); return true; }
      if (scrubMs != null) { scrubMs = null; updateSeek(); return true; }
      close();
      return true;
    },
    destroy: () => { if (!closed) { closed = true; teardown(); document.body.classList.remove('video-mode'); document.removeEventListener('visibilitychange', onVisibility); window.clearInterval(tickTimer); } },
  };

  tickTimer = window.setInterval(tick, 1000);
  if (req.kind === 'live') showBanner();
  start();
  return screen;
}

function errText(e: unknown): string {
  const m = e ? (e as Error).message : '';
  return m || 'Riprova tra qualche istante.';
}

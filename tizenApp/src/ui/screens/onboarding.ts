// Welcome + disclaimer, "Aggiungi lista" (Accesso al server / Lista M3U) and the first sync (Android ui/onboarding).
import type { SourceInput, SyncState } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, icon, clear } from '../dom.ts';
import { ElementsZone, FocusScope, makeTrack } from '../focus.ts';
import { brandMark, primaryButton, secondaryButton, progressBar, setProgress } from '../components.ts';
import { textField } from '../input.ts';
import { prefs } from '../prefs.ts';
import { phonePanel, type PhonePanel } from './phonePair.ts';

export const DISCLAIMER = 'StePlayer è solo un lettore: non fornisce né vende contenuti. Usa solo liste che hai il diritto di guardare.';
/** First-launch legal gate (same 3 points as Android and iOS). */
export const DISCLAIMER_GATE = [
  "1. StePlayer è esclusivamente un lettore multimediale neutrale: non fornisce, non vende e non include alcun contenuto audiovisivo o abbonamento.",
  "2. Devi disporre dei diritti per riprodurre qualsiasi flusso o lista che inserisci nell'app.",
  "3. Lo sviluppatore declina ogni responsabilità per l'uso dell'app con contenuti protetti dal diritto d'autore senza autorizzazione.",
];
const PRIVACY = 'Tutti i dati restano su questo televisore. Nessun account, nessun tracciamento.';

/**
 * Same wording as the Android errorMessage(): errors from the core that carry a `kind` already have the
 * full, precise Italian sentence as message (core/net/networkErrors.ts); plain errors get a fallback.
 */
export function errorMessage(e: unknown): string {
  const err = e as { kind?: string; message?: string } | null;
  const msg = err && err.message;
  if (err && err.kind && msg) return msg;
  if (!msg) return "Impossibile raggiungere il server. Controlla l'indirizzo e la connessione.";
  if (/failed to fetch|network|load failed|timeout/i.test(msg)) return "Impossibile raggiungere il server. Controlla l'indirizzo e la connessione.";
  return msg;
}

function infoCard(iconName: string, text: string): HTMLElement {
  return h('div', { class: 'row', style: 'align-items:flex-start;background:var(--surface);border:2px solid var(--outline);border-radius:32px;padding:32px;margin-bottom:24px' },
    h('span', { style: 'color:var(--accent2);margin-right:24px' }, icon(iconName, 44)),
    h('div', { class: 't-body-m' }, text));
}

function gateCard(): HTMLElement {
  return h('div', { class: 'row', style: 'align-items:flex-start;background:var(--surface);border:2px solid var(--outline);border-radius:32px;padding:28px 32px;margin-bottom:20px' },
    h('span', { style: 'color:var(--accent2);margin-right:24px' }, icon('info', 44)),
    h('div', { class: 'col' }, DISCLAIMER_GATE.map((t, i) => h('div', { class: 't-body-m', style: i ? 'margin-top:12px' : '' }, t))));
}

export function welcomeScreen(onContinue: () => void): Screen {
  let confirmed = prefs.get('disclaimerAccepted') === '1';
  const box = h('div', { class: 'box' }, icon(confirmed ? 'check' : 'check', 36));
  const check = h('div', { class: `checkrow s102${confirmed ? ' checked' : ''}`, 'data-focus': true, 'data-default': confirmed ? null : true },
    box, h('div', { class: 't-body-m' }, 'Confermo che userò StePlayer solo con contenuti che ho il diritto di guardare'));
  const proceed = primaryButton('Ho capito e accetto', () => {
    prefs.set('disclaimerAccepted', '1');
    onContinue();
  }, undefined, { disabled: !confirmed });
  proceed.style.width = '100%';
  const sync = () => {
    check.classList.toggle('checked', confirmed);
    (box.firstChild as HTMLElement).style.visibility = confirmed ? 'visible' : 'hidden';
    if (confirmed) proceed.removeAttribute('data-disabled'); else proceed.setAttribute('data-disabled', '');
  };
  check.addEventListener('click', () => { confirmed = !confirmed; sync(); });
  sync();
  const column = h('div', { class: 'col', style: 'width:1120px;align-items:center' },
    brandMark(128, 'margin-bottom:28px'),
    h('div', { class: 't-display', style: 'text-align:center' }, 'Benvenuto in StePlayer'),
    h('div', { class: 't-body-l c2', style: 'text-align:center;margin:12px 0 32px' }, 'Lettore multimediale per le tue liste. Veloce, pulito e privato.'),
    h('div', { style: 'width:100%' }, gateCard(), infoCard('lock', PRIVACY)),
    h('div', { style: 'width:100%;margin:4px 0 24px' }, check),
    h('div', { style: 'width:100%' }, proceed));
  const el = h('div', { class: 'screen', style: 'display:flex;align-items:center;justify-content:center' },
    h('div', { style: 'position:absolute;left:540px;top:-200px;width:840px;height:840px;background:radial-gradient(rgba(255,162,31,0.30), transparent 70%)' }),
    column);
  const zone = new ElementsZone(column);
  return { el, scope: new FocusScope([zone]) };
}

// ---------------------------------------------------------------------------------------------------------

export function addSourceScreen(opts: { editId?: number; onSaved: (id: number, isNew: boolean) => void; canGoBack: boolean }): Screen {
  const editing = opts.editId != null;
  let type: 'xtream' | 'm3u' = 'xtream';
  let loading = false;
  let advanced = false;
  // New lists: the phone is the primary option (like Android TV / Apple TV), the remote the alternative.
  let mode: 'phone' | 'remote' = editing ? 'remote' : 'phone';
  let panel: PhonePanel | null = null;

  const name = textField({ label: 'Nome lista (qualsiasi cosa)' });
  const server = textField({ label: 'Server (es. http://server.esempio:8080)', type: 'url' });
  const username = textField({ label: 'Username' });
  const password = textField({ label: editing ? 'Password (lascia vuoto per non cambiarla)' : 'Password', type: 'password' });
  const m3uUrl = textField({ label: 'URL lista', type: 'url' });
  const epgUrl = textField({ label: 'URL EPG (opzionale)', type: 'url' });
  const userAgent = textField({ label: 'User-Agent (opzionale)' });

  const tabX = h('div', { 'data-focus': true, class: 's102' }, 'Accesso al server');
  const tabM = h('div', { 'data-focus': true, class: 's102' }, 'Lista M3U');
  const tabs = h('div', { class: 'tabs2' }, tabX, tabM);
  const modePhone = h('div', { 'data-focus': true, class: 's102' }, 'Usa il telefono');
  const modeRemote = h('div', { 'data-focus': true, class: 's102' }, 'Inserisci con il telecomando');
  const modes = h('div', { class: 'tabs2' }, modePhone, modeRemote);
  const smartThingsHint = h('div', { class: 't-body-s c2', style: 'margin:0 8px 24px' },
    "Suggerimento: quando si apre la tastiera del televisore puoi scrivere (o incollare) dal telefono con l'app Samsung SmartThings.");
  const hint = h('div', { class: 't-label-l c2', style: 'margin:0 8px 16px' }, 'Accedi con indirizzo, username e password del tuo server');
  const advancedToggle = h('div', { class: 'row s102', 'data-focus': true, style: 'padding:12px 8px;border-radius:24px;color:var(--text2);display:inline-flex' },
    h('span', { class: 't-label-l' }, 'Avanzate'), icon('expand_more', 48));
  const error = h('div', { class: 'row hidden', style: 'background:rgba(255,59,92,0.12);border-radius:24px;padding:28px;margin:8px 0 24px' },
    h('span', { style: 'color:var(--live);margin-right:20px' }, icon('error_outline', 48)), h('div', { class: 't-body-m grow' }));
  const save = primaryButton(editing ? 'Salva' : 'Salva e connetti', () => submit());
  save.style.width = '100%';
  const busy = h('div', { class: 'row hidden', style: 'justify-content:center;padding:24px' },
    h('div', { class: 'spinner small', style: 'margin-right:24px' }), h('span', { class: 'c2 t-body-m' }, 'Connessione in corso…'));
  const form = h('div', { class: 'col', style: 'width:1240px;margin:0 auto;padding:16px 40px 80px' });
  const scroller = h('div', { style: 'position:absolute;left:0;right:0;top:128px;bottom:0;overflow:hidden' }, form);
  makeTrack(scroller);

  const render = () => {
    clear(form);
    modePhone.classList.toggle('selected', mode === 'phone');
    modeRemote.classList.toggle('selected', mode === 'remote');
    if (!editing) form.appendChild(modes);
    if (mode === 'phone') {
      if (!panel) panel = phonePanel((id) => opts.onSaved(id, true), () => scope.ensure(formZone));
      form.appendChild(panel.el);
      return;
    }
    if (panel) { panel.destroy(); panel = null; }
    tabX.classList.toggle('selected', type === 'xtream');
    tabM.classList.toggle('selected', type === 'm3u');
    if (!editing) form.append(tabs, smartThingsHint);
    form.appendChild(name.el);
    if (type === 'xtream') form.append(hint, server.el, username.el, password.el);
    else form.append(m3uUrl.el, epgUrl.el);
    form.appendChild(advancedToggle);
    if (advanced) form.appendChild(userAgent.el);
    form.append(error, loading ? busy : save,
      h('div', { class: 't-body-s c2', style: 'text-align:center;margin-top:24px' }, DISCLAIMER));
    busy.classList.toggle('hidden', !loading);
  };
  const showError = (msg: string | null) => {
    error.classList.toggle('hidden', !msg);
    (error.lastChild as HTMLElement).textContent = msg || '';
  };

  modePhone.addEventListener('click', () => { if (mode !== 'phone') { mode = 'phone'; render(); } });
  modeRemote.addEventListener('click', () => { if (mode !== 'remote') { mode = 'remote'; render(); } });
  tabX.addEventListener('click', () => { type = 'xtream'; showError(null); render(); });
  tabM.addEventListener('click', () => { type = 'm3u'; showError(null); render(); });
  advancedToggle.addEventListener('click', () => { advanced = !advanced; render(); });

  async function submit() {
    if (loading) return;
    const missing = type === 'xtream'
      ? !server.value.trim() || !username.value.trim() || (!password.value && !editing)
      : !m3uUrl.value.trim();
    if (missing) { showError('Compila tutti i campi obbligatori'); return; }
    const ua = userAgent.value.trim() || null;
    const input: SourceInput = type === 'xtream'
      ? { kind: 'xtream', name: name.value.trim(), serverUrl: server.value.trim(), username: username.value.trim(), password: password.value, userAgent: ua }
      : { kind: 'm3u', name: name.value.trim(), listUrl: m3uUrl.value.trim(), epgUrl: epgUrl.value.trim() || null, userAgent: ua };
    loading = true;
    showError(null);
    render();
    try {
      const core = app().core;
      if (opts.editId == null) {
        const id = await core.sources.add(input);
        opts.onSaved(id, true);
      } else {
        await core.sources.update(opts.editId, input);
        core.sync.sync(opts.editId).catch(() => undefined);
        opts.onSaved(opts.editId, false);
      }
    } catch (e) {
      loading = false;
      render();
      showError(errorMessage(e));
      scope.ensure();
    }
  }

  const top = h('div', { class: 'topbar' },
    opts.canGoBack ? backButton() : h('div', { style: 'width:16px' }),
    h('div', { class: 'title t-headline' }, editing ? 'Modifica lista' : 'Aggiungi lista'));
  const el = h('div', { class: 'screen' }, top, scroller);
  const formZone = new ElementsZone(form, { scroller, axis: 'y', pad: 160 });
  const topZone = new ElementsZone(top);
  const scope = new FocusScope([formZone, topZone]);
  render();

  if (editing && opts.editId != null) {
    app().core.sources.inputFor(opts.editId).then((inp) => {
      if (!inp) return;
      type = inp.kind;
      name.value = inp.name;
      userAgent.value = inp.userAgent || '';
      if (inp.kind === 'xtream') { server.value = inp.serverUrl; username.value = inp.username; }
      else { m3uUrl.value = inp.listUrl; epgUrl.value = inp.epgUrl || ''; }
      advanced = !!inp.userAgent;
      render();
    }).catch(() => undefined);
  }
  return {
    el, scope,
    onShow: () => scope.ensure(formZone),
    onKey: (k) => {
      if (k.key === 'digit' && panel && mode === 'phone') { panel.typeDigit(k.digit); return true; }
      return false;
    },
    destroy: () => { if (panel) panel.destroy(); },
  };
}

function backButton(): HTMLElement {
  const b = h('div', { class: 'iconbtn s110', 'data-focus': true }, icon('arrow_back', 48));
  b.addEventListener('click', () => app().pop());
  return b;
}

// ---------------------------------------------------------------------------------------------------------

/** Text of each sync step (first sync screen and the refresh popup). */
export const STEP_LABEL: Record<string, string> = {
  AUTHENTICATING: 'Accesso al server…',
  LIVE: 'Scarico i canali live…',
  MOVIES: 'Scarico i film…',
  SERIES: 'Scarico le serie TV…',
  EPG: 'Scarico la guida TV…',
  SAVING: 'Salvataggio…',
};

export function syncScreen(sourceId: number, onDone: () => void): Screen {
  const content = h('div', { class: 'col', style: 'width:960px;align-items:center;text-align:center' });
  const el = h('div', { class: 'screen', style: 'display:flex;align-items:center;justify-content:center' }, content);
  const zone = new ElementsZone(content);
  const scope = new FocusScope([zone]);
  const core = app().core;
  let unsub: (() => void) | null = null;

  const running = () => {
    clear(content);
    const label = h('div', { class: 't-body-l c2', style: 'margin:16px 0 48px' }, STEP_LABEL.AUTHENTICATING);
    const bar = progressBar(0.02, 'thin');
    bar.style.width = '100%';
    content.append(
      h('div', { style: 'width:160px;height:160px;border-radius:48px;background:var(--grad);display:flex;align-items:center;justify-content:center;color:var(--onaccent);margin-bottom:48px' }, icon('cloud_sync', 80)),
      h('div', { class: 't-headline-s' }, 'Preparazione della libreria'), label, bar);
    unsub = core.sync.onState((s: SyncState) => {
      if (s.kind === 'running') {
        label.textContent = STEP_LABEL[s.step] || STEP_LABEL.AUTHENTICATING;
        setProgress(bar, Math.max(0.02, s.progress));
      }
    });
  };
  const failed = (e: unknown) => {
    clear(content);
    const retry = primaryButton('Riprova', () => start());
    const skip = secondaryButton('Continua comunque', () => onDone());
    retry.style.width = '100%';
    skip.style.width = '100%';
    skip.style.marginTop = '20px';
    retry.setAttribute('data-default', '');
    content.append(
      h('span', { style: 'color:var(--live)' }, icon('error_outline', 112)),
      h('div', { class: 't-headline-s', style: 'margin-top:32px' }, 'Sincronizzazione non riuscita'),
      h('div', { class: 't-body-m c2', style: 'margin:16px 0 48px' }, errorMessage(e)),
      retry, skip);
    scope.ensure();
  };
  const start = () => {
    running();
    core.sync.sync(sourceId).then(() => { unsub?.(); onDone(); }, (e) => { unsub?.(); failed(e); });
  };
  start();
  return { el, scope, destroy: () => unsub?.() };
}

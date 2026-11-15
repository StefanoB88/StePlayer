// StePlayer for Samsung Smart TV (Tizen web app) — entry point.
import './polyfills.ts';
import { createCore } from './core/index.ts';
import { App, setApp } from './ui/app.ts';
import { h } from './ui/dom.ts';
import { mapKey, registerRemoteKeys, type KeyPress } from './ui/keys.ts';
import { handleEditingKey, isEditing } from './ui/input.ts';
import { session } from './ui/session.ts';
import { openShell } from './ui/shell.ts';
import { addSourceScreen, syncScreen, welcomeScreen } from './ui/screens/onboarding.ts';
import { prefs } from './ui/prefs.ts';
import { watchNetwork } from './ui/tvsystem.ts';

declare const __DEV__: boolean;

let app: App | null = null;

function startOnboarding(a: App): void {
  const addList = () => addSourceScreen({
    canGoBack: false,
    onSaved: (id) => a.reset(syncScreen(id, () => openShell())),
  });
  if (prefs.get('disclaimerAccepted') === '1') a.reset(addList());
  else a.reset(welcomeScreen(() => a.reset(addList())));
}

// ----- keys: OK acts on release so a long press (≥ 600 ms) can toggle favourites, like Android's long click.
let enterTimer = 0;
let enterLongFired = false;
let enterDown = false;

function dispatch(k: KeyPress): boolean {
  return app ? app.handleKey(k) : false;
}

function onKeyDown(e: KeyboardEvent): void {
  if (isEditing()) {
    if (handleEditingKey(e)) return; // typing in the TV keyboard
    // The keyboard was closed by an arrow: let the arrow move the focus.
  }
  const k = mapKey(e);
  if (k.key === 'enter') {
    e.preventDefault();
    if (enterDown) return; // auto-repeat while held
    enterDown = true;
    enterLongFired = false;
    window.clearTimeout(enterTimer);
    enterTimer = window.setTimeout(() => {
      enterTimer = 0;
      enterLongFired = true;
      if (!dispatch({ key: 'enterLong', digit: -1, raw: e })) dispatch({ key: 'enter', digit: -1, raw: e });
    }, 600);
    return;
  }
  if (k.key === 'other') return;
  if (dispatch(k)) { e.preventDefault(); return; }
  if (k.key === 'back') e.preventDefault(); // never leave the app on Back unexpectedly
}

function onKeyUp(e: KeyboardEvent): void {
  if (e.keyCode !== 13 && e.keyCode !== 65376) return;
  if (!enterDown) return;
  enterDown = false;
  if (enterTimer) {
    window.clearTimeout(enterTimer);
    enterTimer = 0;
    if (!enterLongFired) dispatch({ key: 'enter', digit: -1, raw: e });
  }
}

async function boot(): Promise<void> {
  registerRemoteKeys();
  watchNetwork();
  document.addEventListener('keydown', onKeyDown, true);
  document.addEventListener('keyup', onKeyUp, true);
  const root = document.getElementById('app') as HTMLElement;
  try {
    const nav = navigator as Navigator & { languages?: string[] };
    const deviceLanguages = (nav.languages && nav.languages.length ? nav.languages : [nav.language || 'it']).map((l) => l.split('-')[0]);
    const core = await createCore({ deviceLanguages });
    app = new App(core, root);
    setApp(app);
    await session.reload();
    core.subscribe('sources', () => { session.reload().catch(() => undefined); });
    if (!session.source) {
      startOnboarding(app);
    } else {
      openShell();
      core.sync.syncIfStale(session.source.id).catch(() => undefined);
    }
  } catch (e) {
    root.appendChild(h('div', { class: 'screen', style: 'display:flex;align-items:center;justify-content:center;flex-direction:column;text-align:center' },
      h('div', { class: 't-headline-s' }, 'Impossibile avviare StePlayer'),
      h('div', { class: 't-body-m c2', style: 'margin-top:16px;max-width:1100px' }, (e as Error).message || String(e))));
    if (__DEV__) console.error(e);
  }
}

boot();

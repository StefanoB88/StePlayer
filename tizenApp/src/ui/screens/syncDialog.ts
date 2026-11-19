// Blocking popup for a sync the user asked for ("Aggiorna" on Home, "Sincronizza ora" in Settings).
// While the sync runs every remote key is swallowed (Back included) and nothing behind can get the
// focus; on failure it shows the precise message with "Riprova" / "Chiudi". Background syncs
// (syncIfStale) never use it.
import type { SyncState } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { clear, h, icon } from '../dom.ts';
import { ElementsZone, FocusScope } from '../focus.ts';
import type { KeyPress } from '../keys.ts';
import { primaryButton, progressBar, secondaryButton, setProgress } from '../components.ts';
import { errorMessage, STEP_LABEL } from './onboarding.ts';

let open = false;

/**
 * Runs a full sync of `sourceId` inside the popup. Resolves true after a successful sync (popup closed
 * and `successToast` shown), false when the user closes it after a failure.
 */
export function syncDialog(sourceId: number, successToast: string): Promise<boolean> {
  if (open) return Promise.resolve(false);
  open = true;
  const core = app().core;
  return new Promise<boolean>((resolve) => {
    let running = false;
    let unsub: (() => void) | null = null;

    const title = h('div', { class: 'dtitle t-headline-s' });
    const body = h('div', { class: 'dbody col' });
    const actions = h('div', { class: 'dactions' });
    const box = h('div', { class: 'dialog' }, title, body, actions);
    const scope = new FocusScope([new ElementsZone(actions)]);

    const finish = (ok: boolean) => {
      resolve(ok);
      app().closeDialog(screen); // → destroy()
      // The screen behind gets its focus back (it may have re-rendered meanwhile).
      app().top?.scope.ensure();
      if (ok) app().toast(successToast);
    };

    const showRunning = () => {
      running = true;
      clear(body);
      clear(actions);
      actions.style.display = 'none'; // no buttons while running: nothing to focus
      title.textContent = 'Aggiornamento della lista';
      const label = h('div', { class: 't-body-l c2' }, STEP_LABEL.AUTHENTICATING);
      const bar = progressBar(0.02);
      bar.style.flex = '1 1 auto';
      const percent = h('div', { class: 't-title-m', style: 'min-width:112px;text-align:right;margin-left:24px' }, '2%');
      body.append(label, h('div', { class: 'row', style: 'margin-top:32px' }, bar, percent));
      unsub = core.sync.onState((s: SyncState) => {
        if (s.kind !== 'running') return;
        const p = Math.max(0.02, Math.min(1, s.progress));
        label.textContent = STEP_LABEL[s.step] || STEP_LABEL.AUTHENTICATING;
        setProgress(bar, p);
        percent.textContent = `${Math.round(p * 100)}%`;
      });
    };

    const showFailed = (e: unknown) => {
      running = false;
      clear(body);
      clear(actions);
      actions.style.display = '';
      title.textContent = 'Aggiornamento non riuscito';
      body.append(h('div', { class: 'row', style: 'align-items:flex-start' },
        h('span', { style: 'color:var(--live);margin-right:24px;flex:0 0 auto' }, icon('error_outline', 56)),
        h('div', { class: 't-body-l c2' }, errorMessage(e))));
      const close = secondaryButton('Chiudi', () => finish(false));
      const retry = primaryButton('Riprova', () => start());
      retry.setAttribute('data-default', '');
      actions.append(close, retry);
      scope.ensure(); // "Riprova" (data-default): the previous buttons are gone
    };

    const start = () => {
      showRunning();
      core.sync.sync(sourceId).then(
        () => finish(true),
        (e) => { unsub?.(); unsub = null; showFailed(e); },
      );
    };

    const screen: Screen = {
      el: h('div', { class: 'dialog-scrim' }, box),
      scope,
      // Every key stops here: nothing reaches the screens behind (App.handleKey asks the top dialog first).
      onKey: (k: KeyPress) => {
        if (running) return true;
        if (k.key === 'back') { finish(false); return true; }
        // A long OK must still press the focused button (main.ts sends enterLong first).
        scope.handle(k.key === 'enterLong' ? { key: 'enter', digit: -1, raw: k.raw } : k);
        return true;
      },
      onBack: () => true,
      // Also reached if the whole stack is reset under the popup: never leave the caller waiting.
      destroy: () => { unsub?.(); unsub = null; open = false; resolve(false); },
    };

    app().openDialog(screen);
    start();
  });
}

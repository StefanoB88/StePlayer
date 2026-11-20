// Magic Remote (LG webOS) and mouse support. The pointer highlights what is under it with the same `.focused`
// style as the remote, so the arrows continue from there; a click activates it; the wheel scrolls lists and pages
// (one step per notch, like the arrows). Elements keep their own click handlers; virtualised rows are activated
// through their zone.
import type { App } from './app.ts';
import type { Zone } from './focus.ts';
import type { KeyPress } from './keys.ts';

let cursorVisible = false;
let lastClickAt = 0;

/** True while the Magic Remote pointer is on screen (OK then clicks what is under it). */
export function isCursorVisible(): boolean { return cursorVisible; }
/** Time of the last pointer click (OK on the Magic Remote can also send Enter: never act twice). */
export function lastPointerClick(): number { return lastClickAt; }

/** Most specific focusable zone of the active screen containing `target`. */
function zoneAt(app: App, target: HTMLElement): Zone | null {
  const screen = app.active;
  if (!screen) return null;
  let best: Zone | null = null;
  for (const z of screen.scope.zones) {
    if (!z.el.contains(target) || !z.canFocus()) continue;
    if (!best || best.el.contains(z.el)) best = z;
  }
  return best;
}

export function installPointer(getApp: () => App | null, dispatch: (k: KeyPress) => boolean): void {
  // webOS: the system tells when the pointer appears or hides (it hides when the arrows are used).
  document.addEventListener('cursorStateChange', (e: Event) => {
    const d = (e as CustomEvent<{ visibility?: boolean }>).detail;
    cursorVisible = !!(d && d.visibility);
  });
  document.addEventListener('keydown', (e) => {
    if (e.keyCode === 1536) cursorVisible = true; // webOS "cursor shown" / "cursor hidden" pseudo keys
    else if (e.keyCode === 1537) cursorVisible = false;
    else if (e.keyCode >= 37 && e.keyCode <= 40) cursorVisible = false;
  }, true);

  let pendingMove: HTMLElement | null = null;
  let moveFrame = 0;
  document.addEventListener('mousemove', (e) => {
    cursorVisible = true;
    pendingMove = e.target as HTMLElement;
    if (moveFrame) return;
    moveFrame = window.requestAnimationFrame(() => {
      moveFrame = 0;
      const app = getApp();
      const target = pendingMove;
      if (!app || !target || !app.active) return;
      const screen = app.active;
      screen.onPointer?.('move');
      const zone = zoneAt(app, target);
      if (zone) screen.scope.pointAt(zone, target);
    });
  }, true);

  document.addEventListener('click', (e) => {
    // Clicks synthesised by element.click() (remote OK) have no pointer position and are left alone.
    if (e.clientX === 0 && e.clientY === 0 && e.screenX === 0 && e.screenY === 0) return;
    lastClickAt = Date.now();
    const app = getApp();
    const screen = app && app.active;
    if (!app || !screen) return;
    const target = e.target as HTMLElement;
    if (screen.onPointer && screen.onPointer('click')) { e.preventDefault(); e.stopPropagation(); return; }
    const zone = zoneAt(app, target);
    if (!zone) return;
    screen.scope.pointAt(zone, target);
    if (zone.pointerActivate && zone.pointerActivate(target)) { e.preventDefault(); e.stopPropagation(); }
    // Other elements: their own click handler runs (the event continues).
  }, true);

  let lastWheel = 0;
  document.addEventListener('wheel', (e) => {
    e.preventDefault();
    const now = Date.now();
    if (now - lastWheel < 90) return; // one step per notch, even with fast wheels
    lastWheel = now;
    const app = getApp();
    const screen = app && app.active;
    if (!app || !screen) return;
    // The item under the pointer already has the focus (mousemove): each notch moves on from the current focus.
    const horizontal = Math.abs(e.deltaX) > Math.abs(e.deltaY);
    const delta = horizontal ? e.deltaX : e.deltaY;
    if (!delta) return;
    const key = horizontal ? (delta > 0 ? 'right' : 'left') : (delta > 0 ? 'down' : 'up');
    dispatch({ key, digit: -1, raw: new KeyboardEvent('keydown') });
  }, { capture: true, passive: false } as AddEventListenerOptions);
}

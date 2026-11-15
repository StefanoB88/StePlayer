// Screen stack, dialogs, toasts and global key dispatch.
import type { Core } from '../core/api.ts';
import { FocusScope } from './focus.ts';
import type { KeyPress } from './keys.ts';
import { h } from './dom.ts';

export interface Screen {
  el: HTMLElement;
  scope: FocusScope;
  /** Keys before the focus scope (player shortcuts, colour keys...). */
  onKey?(k: KeyPress): boolean;
  /** Back pressed: return true when handled internally (close a drawer...). */
  onBack?(): boolean;
  /** Became the top screen (again). */
  onShow?(): void;
  /** Covered by another screen. */
  onHide?(): void;
  destroy?(): void;
}

export class App {
  readonly core: Core;
  readonly root: HTMLElement;
  private stack: Screen[] = [];
  private dialogs: Screen[] = [];
  private toastEl: HTMLElement | null = null;
  private toastTimer = 0;

  constructor(core: Core, root: HTMLElement) {
    this.core = core;
    this.root = root;
  }

  get top(): Screen | null { return this.stack[this.stack.length - 1] || null; }

  push(screen: Screen): void {
    const prev = this.top;
    if (prev) { prev.onHide?.(); prev.el.style.visibility = 'hidden'; }
    this.stack.push(screen);
    this.root.appendChild(screen.el);
    screen.scope.ensure();
    screen.onShow?.();
  }

  /** Replace everything (onboarding → shell). */
  reset(screen: Screen): void {
    while (this.dialogs.length) this.closeDialog();
    while (this.stack.length) {
      const s = this.stack.pop()!;
      s.destroy?.();
      s.el.remove();
    }
    this.push(screen);
  }

  pop(): void {
    const s = this.stack.pop();
    if (!s) return;
    s.onHide?.();
    s.destroy?.();
    s.el.remove();
    const top = this.top;
    if (top) {
      top.el.style.visibility = '';
      top.scope.ensure();
      top.onShow?.();
    }
  }

  /** Pops `screen` if it is on the stack (e.g. the player after an error). */
  remove(screen: Screen): void {
    if (this.top === screen) { this.pop(); return; }
    const i = this.stack.indexOf(screen);
    if (i >= 0) { this.stack.splice(i, 1); screen.destroy?.(); screen.el.remove(); }
  }

  openDialog(d: Screen): void {
    this.dialogs.push(d);
    this.root.appendChild(d.el);
    d.scope.ensure();
    d.onShow?.();
  }

  closeDialog(d?: Screen): void {
    const target = d || this.dialogs[this.dialogs.length - 1];
    if (!target) return;
    const i = this.dialogs.indexOf(target);
    if (i < 0) return;
    this.dialogs.splice(i, 1);
    target.destroy?.();
    target.el.remove();
  }

  get hasDialog(): boolean { return this.dialogs.length > 0; }

  toast(message: string, ms = 3200): void {
    if (!this.toastEl) {
      this.toastEl = h('div', { class: 'toast' });
      document.body.appendChild(this.toastEl);
    }
    this.toastEl.textContent = message;
    this.toastEl.style.display = '';
    window.clearTimeout(this.toastTimer);
    this.toastTimer = window.setTimeout(() => { if (this.toastEl) this.toastEl.style.display = 'none'; }, ms);
  }

  /** Global dispatch: dialog → top screen custom keys → focus scope → back. */
  handleKey(k: KeyPress): boolean {
    const target = this.dialogs[this.dialogs.length - 1] || this.top;
    if (!target) return false;
    if (target.onKey && target.onKey(k)) return true;
    if (target.scope.handle(k)) return true;
    if (k.key === 'back') {
      if (target.onBack && target.onBack()) return true;
      if (this.dialogs.length && target === this.dialogs[this.dialogs.length - 1]) { this.closeDialog(); return true; }
      if (this.stack.length > 1) { this.pop(); return true; }
      return false;
    }
    return false;
  }
}

let current: App | null = null;
export function setApp(a: App): void { current = a; }
export function app(): App {
  if (!current) throw new Error('app not started');
  return current;
}

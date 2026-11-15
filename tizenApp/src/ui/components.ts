// UI building blocks, 1:1 with the Android app's ui/components (sizes in px = dp x 2).
import { sizedArtwork } from '../core/util/artworkUrl.ts';
import { h, icon } from './dom.ts';
import { ElementsZone, FocusScope } from './focus.ts';
import { app, type Screen } from './app.ts';
import type { KeyPress } from './keys.ts';

export function initials(title: string): string {
  const parts = title.split(/[\s\-_]+/).filter((p) => p && /[A-Za-z0-9À-ÿ]/.test(p[0]));
  const s = parts.slice(0, 2).map((p) => p[0].toUpperCase()).join('');
  return s || '•';
}

/** Network artwork with an initials placeholder underneath (shown while loading and on error). */
export function artImage(url: string | null | undefined, title: string, opts: { fit?: boolean; initials?: boolean; cls?: string } = {}): HTMLElement {
  const box = h('div', { class: `art${opts.fit ? ' fit' : ''}${opts.cls ? ` ${opts.cls}` : ''}` });
  if (opts.initials !== false) box.appendChild(h('div', { class: 'initials' }, initials(title)));
  if (url && /^https?:\/\//i.test(url)) {
    const img = document.createElement('img');
    img.alt = '';
    (img as HTMLImageElement & { decoding: string }).decoding = 'async';
    img.style.opacity = '0';
    // Decode before showing (off the main thread, while the image is still ~600 px away from the screen):
    // the TV then never stalls a frame decoding a JPEG while it draws the grid.
    img.onload = () => {
      const anyImg = img as HTMLImageElement & { decode?: () => Promise<void> };
      if (anyImg.decode) anyImg.decode().then(() => { img.style.opacity = '1'; }, () => { img.style.opacity = '1'; });
      else img.style.opacity = '1';
    };
    // A resized CDN variant that fails falls back to the URL the list gave; anything else shows the initials.
    img.onerror = () => { if (img.src !== url && img.getAttribute('data-orig') !== '1') { img.setAttribute('data-orig', '1'); img.src = url; } else img.remove(); };
    box.appendChild(img);
    lazyLoad(img, url);
  }
  return box;
}

// Images load only when they are about to enter the screen (a Home row builds 30 cards, ~6 are visible):
// fewer downloads and JPEG decodes on the TV's weak CPU. IntersectionObserver follows transforms (tracks).
let observer: IntersectionObserver | null = null;
const pendingSrc = new WeakMap<Element, string>();

function lazyLoad(img: HTMLImageElement, url: string): void {
  if (typeof IntersectionObserver === 'undefined') { img.src = url; return; }
  if (!observer) {
    observer = new IntersectionObserver((entries) => {
      for (const e of entries) {
        if (!e.isIntersecting) continue;
        const target = e.target as HTMLImageElement;
        const src = pendingSrc.get(target);
        observer!.unobserve(target);
        if (src) {
          pendingSrc.delete(target);
          // Layout is clean inside the observer callback, so reading the slot width costs nothing.
          const slot = target.parentElement ? target.parentElement.clientWidth : 0;
          target.src = sizedArtwork(src, Math.round(slot * (window.devicePixelRatio || 1)));
        }
      }
    }, { rootMargin: '600px 900px 600px 900px' });
  }
  pendingSrc.set(img, url);
  observer.observe(img);
}

export function progressBar(fraction: number, cls = ''): HTMLElement {
  const fill = h('div', { style: `width:${Math.round(Math.max(0, Math.min(1, fraction)) * 1000) / 10}%` });
  return h('div', { class: `progress ${cls}`.trim() }, fill);
}

export function setProgress(bar: HTMLElement, fraction: number): void {
  const fill = bar.firstChild as HTMLElement | null;
  if (fill) fill.style.width = `${Math.round(Math.max(0, Math.min(1, fraction)) * 1000) / 10}%`;
}

export function liveBadge(): HTMLElement { return h('span', { class: 'live-badge' }, 'LIVE'); }

export interface PosterOpts {
  title: string; image: string | null; subtitle?: string | null; rating?: string | null; progress?: number | null;
  width?: number; focusable?: boolean; onClick?: () => void;
}

/** 2:3 poster card (Android PosterCard). */
export function posterCard(o: PosterOpts): HTMLElement {
  const art = artImage(o.image, o.title);
  if (o.rating) art.appendChild(h('div', { class: 'rating' }, icon('star', 24), o.rating));
  if (o.progress && o.progress > 0) { const p = progressBar(o.progress); p.className += ' card-progress'; art.appendChild(p); }
  const el = h('div', { class: 'poster', 'data-focus': o.focusable === false ? null : true, style: o.width ? `width:${o.width}px` : null },
    art,
    h('div', { class: 'meta' },
      h('div', { class: 't-title-s ellipsis' }, o.title),
      o.subtitle ? h('div', { class: 't-body-s c2 ellipsis' }, o.subtitle) : null));
  if (o.onClick) el.addEventListener('click', o.onClick);
  return el;
}

export interface WideOpts {
  title: string; image: string | null; subtitle?: string | null; progress?: number | null; live?: boolean; fit?: boolean;
  width: number; onClick?: () => void;
}

/** 16:9 card for "continue watching" and channels (Android WideCard). */
export function wideCard(o: WideOpts): HTMLElement {
  const art = artImage(o.image, o.title, { fit: o.fit });
  art.appendChild(h('div', { class: 'scrim' }));
  if (o.live) art.appendChild(liveBadge());
  if (o.progress && o.progress > 0) { const p = progressBar(o.progress); p.className += ' card-progress'; art.appendChild(p); }
  const el = h('div', { class: 'wide', 'data-focus': true, style: `width:${o.width}px` },
    art,
    h('div', { class: 'meta' },
      h('div', { class: 't-title-s ellipsis' }, o.title),
      o.subtitle ? h('div', { class: 't-body-s c2 ellipsis' }, o.subtitle) : null));
  if (o.onClick) el.addEventListener('click', o.onClick);
  return el;
}

export function primaryButton(text: string, onClick: () => void, iconName?: string, opts: { disabled?: boolean } = {}): HTMLElement {
  const el = h('div', { class: 'btn s104', 'data-focus': true, 'data-disabled': opts.disabled || null },
    iconName ? icon(iconName, 40) : null, text);
  el.addEventListener('click', () => { if (!el.hasAttribute('data-disabled')) onClick(); });
  return el;
}

export function secondaryButton(text: string, onClick: () => void, iconName?: string, opts: { tint?: string; disabled?: boolean } = {}): HTMLElement {
  const ic = iconName ? icon(iconName, 40) : null;
  if (ic && opts.tint) ic.style.color = opts.tint;
  const el = h('div', { class: `btn secondary s104${text ? '' : ' icon-only'}`, 'data-focus': true, 'data-disabled': opts.disabled || null }, ic, text || null);
  el.addEventListener('click', () => { if (!el.hasAttribute('data-disabled')) onClick(); });
  return el;
}

export function iconButton(name: string, label: string, onClick: () => void, opts: { size?: number; bg?: boolean; tint?: string } = {}): HTMLElement {
  const size = opts.size || 88;
  const el = h('div', { class: `iconbtn s110${opts.bg ? ' bg' : ''}`, 'data-focus': true, title: label, style: `width:${size}px;height:${size}px` },
    icon(name, Math.round(size * 0.55)));
  if (opts.tint) el.style.color = opts.tint;
  el.addEventListener('click', onClick);
  return el;
}

export function chip(label: string, selected: boolean, onClick: () => void): HTMLElement {
  const el = h('div', { class: `chip s104${selected ? ' selected' : ''}`, 'data-focus': true }, label);
  el.addEventListener('click', onClick);
  return el;
}

export function emptyState(iconName: string, text: string, action?: HTMLElement): HTMLElement {
  return h('div', { class: 'empty' },
    h('div', { class: 'bubble' }, icon(iconName, 68)),
    h('div', { class: 'text t-body-l c2' }, text),
    action || null);
}

export function loadingBox(): HTMLElement {
  return h('div', { class: 'loading' }, h('div', { class: 'spinner' }));
}

export function sectionTitle(text: string): HTMLElement {
  return h('div', { class: 'section-title t-title-l' }, text);
}

// ---------------------------------------------------------------------------------------------------------
// Dialogs

function dialogScreen(body: HTMLElement, zones: (s: FocusScope) => void, onBack?: () => boolean): Screen {
  const el = h('div', { class: 'dialog-scrim' }, body);
  const scope = new FocusScope();
  zones(scope);
  return { el, scope, onBack };
}

/** Radio list (Android OptionsDialog): select closes the dialog. */
export function optionsDialog(title: string, options: { label: string; checked: boolean }[], onSelect: (index: number) => void): void {
  const list = h('div', { class: 'dbody col' });
  options.forEach((o, i) => {
    const row = h('div', { class: `option s102${o.checked ? ' checked' : ''}`, 'data-focus': true, 'data-default': o.checked || null },
      icon(o.checked ? 'radio_button_checked' : 'radio_button_unchecked', 48), h('span', { class: 'ellipsis' }, o.label));
    row.addEventListener('click', () => { app().closeDialog(screen); onSelect(i); });
    list.appendChild(row);
  });
  const cancel = h('div', { class: 'textbtn s104', 'data-focus': true }, 'Annulla');
  cancel.addEventListener('click', () => app().closeDialog(screen));
  const box = h('div', { class: 'dialog' }, h('div', { class: 'dtitle t-headline-s' }, title), list, h('div', { class: 'dactions' }, cancel));
  const screen = dialogScreen(box, (s) => {
    const listZone = new ElementsZone(list, { scroller: list, axis: 'y', pad: 24 });
    const actions = new ElementsZone(box.querySelector('.dactions') as HTMLElement);
    s.add(listZone, actions);
  });
  app().openDialog(screen);
}

/** Plain list of actions (Android MenuDialog). */
export function menuDialog(title: string, items: string[], onSelect: (index: number) => void): void {
  const list = h('div', { class: 'dbody col' });
  items.forEach((label, i) => {
    const row = h('div', { class: 'option s102', 'data-focus': true }, h('span', { class: 'ellipsis' }, label));
    row.addEventListener('click', () => { app().closeDialog(screen); onSelect(i); });
    list.appendChild(row);
  });
  const cancel = h('div', { class: 'textbtn s104', 'data-focus': true }, 'Annulla');
  cancel.addEventListener('click', () => app().closeDialog(screen));
  const box = h('div', { class: 'dialog' }, h('div', { class: 'dtitle t-headline-s' }, title), list, h('div', { class: 'dactions' }, cancel));
  const screen = dialogScreen(box, (s) => {
    s.add(new ElementsZone(list, { scroller: list, axis: 'y', pad: 24 }), new ElementsZone(box.querySelector('.dactions') as HTMLElement));
  });
  app().openDialog(screen);
}

/** Confirmation with a primary action (Android AlertDialog). */
export function confirmDialog(title: string, message: string, confirmLabel: string, onConfirm: () => void, cancelLabel = 'Annulla'): Screen {
  const confirm = h('div', { class: 'btn s104', 'data-focus': true, 'data-default': true }, confirmLabel);
  const cancel = h('div', { class: 'btn secondary s104', 'data-focus': true }, cancelLabel);
  const actions = h('div', { class: 'dactions' }, cancel, confirm);
  const box = h('div', { class: 'dialog' },
    h('div', { class: 'dtitle t-headline-s' }, title),
    message ? h('div', { class: 'dbody t-body-l c2' }, message) : null,
    actions);
  const screen = dialogScreen(box, (s) => { s.add(new ElementsZone(actions)); });
  confirm.addEventListener('click', () => { app().closeDialog(screen); onConfirm(); });
  cancel.addEventListener('click', () => app().closeDialog(screen));
  app().openDialog(screen);
  return screen;
}

/**
 * Long text (full plot, cast…) in a dialog: Up/Down scroll it, with a visible scroll bar (Samsung QA #21:
 * text longer than its box must be scrollable). Jumps, no smooth scrolling (software rasterised TVs).
 */
export function textDialog(title: string, paragraphs: (string | null | undefined)[]): void {
  const body = h('div', { class: 'dbody textscroll t-body-l' });
  paragraphs.filter((t): t is string => !!t && !!t.trim()).forEach((t) => body.appendChild(h('p', null, t)));
  const close = h('div', { class: 'btn s104', 'data-focus': true, 'data-default': true }, 'Chiudi');
  close.addEventListener('click', () => app().closeDialog(screen));
  const box = h('div', { class: 'dialog wide' }, h('div', { class: 'dtitle t-headline-s' }, title), body, h('div', { class: 'dactions' }, close));
  const screen = dialogScreen(box, (sc) => { sc.add(new ElementsZone(box.querySelector('.dactions') as HTMLElement)); });
  screen.onKey = (k: KeyPress) => {
    if (k.key !== 'up' && k.key !== 'down') return false;
    body.scrollTop += k.key === 'down' ? 160 : -160;
    return true;
  };
  app().openDialog(screen);
}

/** −/+ stepper (Android DelayDialog). */
export function stepperDialog(title: string, value: number, step: number, format: (v: number) => string, onChange: (v: number) => void): void {
  let v = value;
  const label = h('div', { class: 't-headline', style: 'min-width:240px;text-align:center;margin:0 48px' }, format(v));
  const minus = h('div', { class: 'iconbtn s110', 'data-focus': true, 'data-default': true, style: 'width:104px;height:104px;background:var(--surface)' }, icon('remove', 56));
  const plus = h('div', { class: 'iconbtn s110', 'data-focus': true, style: 'width:104px;height:104px;background:var(--surface)' }, icon('add', 56));
  const set = (nv: number) => { v = nv; label.textContent = format(v); onChange(v); };
  minus.addEventListener('click', () => set(v - step));
  plus.addEventListener('click', () => set(v + step));
  const reset = h('div', { class: 'textbtn s104', 'data-focus': true }, 'Azzera');
  const ok = h('div', { class: 'textbtn s104', 'data-focus': true }, 'OK');
  reset.addEventListener('click', () => set(0));
  ok.addEventListener('click', () => app().closeDialog(screen));
  const stepper = h('div', { class: 'row', style: 'justify-content:center;padding:24px 0' }, minus, label, plus);
  const actions = h('div', { class: 'dactions' }, reset, ok);
  const box = h('div', { class: 'dialog' }, h('div', { class: 'dtitle t-headline-s' }, title), stepper, actions);
  const screen = dialogScreen(box, (s) => { s.add(new ElementsZone(stepper), new ElementsZone(actions)); });
  app().openDialog(screen);
}

/** Wraps a key handler so digits/colour keys can be added to a screen in one place. */
export type KeyHandler = (k: KeyPress) => boolean;

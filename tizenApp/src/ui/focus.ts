// D-pad navigation. A screen is split into zones (rail, category list, channel list, poster grid,
// button bar...). Arrows move inside the focused zone; at its edge the scope moves to the nearest zone in
// that direction (or to an explicit neighbour). Big lists are virtualised: only visible rows exist in the DOM.
import type { KeyPress } from './keys.ts';

export type Dir = 'up' | 'down' | 'left' | 'right';

export interface Zone {
  readonly el: HTMLElement;
  /** Explicit neighbours win over geometry. */
  neighbors?: Partial<Record<Dir, Zone | (() => Zone | null)>>;
  canFocus(): boolean;
  /** Focus enters the zone; `from` is the rect of the previously focused item (nearest pick). */
  enter(from: DOMRect | null, dir: Dir | null): void;
  leave(): void;
  /** Move inside; false at the edge. */
  move(dir: Dir): boolean;
  activate(): void;
  focusedRect(): DOMRect | null;
  /** Optional extra keys while focused (colour keys, long-press replacement...). */
  onKey?(k: KeyPress): boolean;
  /** Magic Remote / mouse: highlights the item under the pointer, without scrolling. False (and no change) when
   *  there is nothing focusable there. */
  pointerFocus?(target: HTMLElement): boolean;
  /** Pointer click on an item without a click handler of its own (virtualised rows). */
  pointerActivate?(target: HTMLElement): boolean;
}

export class FocusScope {
  zones: Zone[] = [];
  current: Zone | null = null;

  constructor(zones: Zone[] = []) { this.zones = zones; }

  add(...zones: Zone[]): void { this.zones.push(...zones); }

  remove(zone: Zone): void {
    this.zones = this.zones.filter((z) => z !== zone);
    if (this.current === zone) { zone.leave(); this.current = null; }
  }

  focus(zone: Zone | null, from: DOMRect | null = null, dir: Dir | null = null): boolean {
    if (!zone || !zone.canFocus()) return false;
    const prevRect = from || (this.current ? this.current.focusedRect() : null);
    if (this.current && this.current !== zone) this.current.leave();
    this.current = zone;
    zone.enter(prevRect, dir);
    return true;
  }

  /** Focus the first focusable zone (or keep the current one if still valid). */
  ensure(preferred?: Zone | null): void {
    if (this.current && this.current.canFocus() && this.zones.indexOf(this.current) >= 0) {
      this.current.enter(null, null);
      return;
    }
    if (preferred && this.focus(preferred)) return;
    for (const z of this.zones) if (this.focus(z)) return;
  }

  /** The pointer is over `target` inside `zone`: that item gets the focus (the remote continues from there). */
  pointAt(zone: Zone, target: HTMLElement): boolean {
    if (!zone.canFocus()) return false;
    if (zone.pointerFocus) {
      const prev = this.current;
      if (!zone.pointerFocus(target)) return false;
      if (prev && prev !== zone) prev.leave();
      this.current = zone;
      return true;
    }
    return this.current === zone || this.focus(zone);
  }

  handle(k: KeyPress): boolean {
    const z = this.current;
    if (!z) { this.ensure(); return this.current != null && k.key !== 'back'; }
    if (z.onKey && z.onKey(k)) return true;
    switch (k.key) {
      case 'up': case 'down': case 'left': case 'right': {
        const dir = k.key;
        if (z.move(dir)) return true;
        const next = this.neighbor(z, dir);
        if (next) { this.focus(next, z.focusedRect(), dir); return true; }
        return true; // edge of the screen: swallow
      }
      case 'enter':
        z.activate();
        return true;
      default:
        return false;
    }
  }

  private neighbor(z: Zone, dir: Dir): Zone | null {
    const explicit = z.neighbors && z.neighbors[dir];
    if (explicit) {
      // An explicit neighbour is final: null (or not focusable) blocks the move instead of guessing by geometry.
      const target = typeof explicit === 'function' ? explicit() : explicit;
      return target && target.canFocus() ? target : null;
    }
    const from = z.focusedRect() || z.el.getBoundingClientRect();
    const cx = from.left + from.width / 2;
    const cy = from.top + from.height / 2;
    let best: Zone | null = null;
    let bestScore = Infinity;
    for (const c of this.zones) {
      if (c === z || !c.canFocus()) continue;
      const r = c.el.getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue;
      let primary: number;
      let secondary: number;
      if (dir === 'right') { if (r.left < from.right - 4) continue; primary = r.left - from.right; secondary = dist1(cy, r.top, r.bottom); }
      else if (dir === 'left') { if (r.right > from.left + 4) continue; primary = from.left - r.right; secondary = dist1(cy, r.top, r.bottom); }
      else if (dir === 'down') { if (r.top < from.bottom - 4) continue; primary = r.top - from.bottom; secondary = dist1(cx, r.left, r.right); }
      else { if (r.bottom > from.top + 4) continue; primary = from.top - r.bottom; secondary = dist1(cx, r.left, r.right); }
      const score = Math.max(0, primary) + secondary * 2;
      if (score < bestScore) { bestScore = score; best = c; }
    }
    return best;
  }
}

function dist1(v: number, a: number, b: number): number {
  return v < a ? a - v : v > b ? v - b : 0;
}

// ---------------------------------------------------------------------------------------------------------
// Scrolling. Samsung TVs rasterise in software: changing scrollTop/scrollLeft of an overflow:hidden box repaints
// (and re-decodes the images of) the whole box on every animation frame. Instead every scroll container holds a
// single `.track` child that is moved with a compositor transform: the TV moves an already painted layer.
// Positions are computed from layout offsets (not from getBoundingClientRect, which follows the running animation),
// so fast repeated key presses always aim at the right place.

const offsets = new WeakMap<HTMLElement, { x: number; y: number }>();
const SCROLL_MS = 160;

function trackOf(container: HTMLElement): HTMLElement | null {
  const t = container.firstElementChild as HTMLElement | null;
  return t && t.classList.contains('track') ? t : null;
}

/** Wraps the current children of `container` in a `.track` (or returns the existing one): append content to it. */
export function makeTrack(container: HTMLElement, cls = ''): HTMLElement {
  const existing = trackOf(container);
  if (existing) return existing;
  const track = document.createElement('div');
  track.className = `track ${cls}`.trim();
  while (container.firstChild) track.appendChild(container.firstChild);
  container.appendChild(track);
  return track;
}

/** Back to the start without animation (content replaced). */
export function resetScroll(container: HTMLElement): void {
  offsets.set(container, { x: 0, y: 0 });
  const t = trackOf(container);
  if (t) { t.style.transition = 'none'; t.style.transform = 'translate3d(0,0,0)'; }
  container.scrollTop = 0;
  container.scrollLeft = 0;
}

/** Current (target) scroll position. */
export function getScroll(container: HTMLElement, axis: 'x' | 'y'): number {
  if (!trackOf(container)) return axis === 'y' ? container.scrollTop : container.scrollLeft;
  const o = offsets.get(container);
  return o ? o[axis] : 0;
}

function maxScroll(container: HTMLElement, track: HTMLElement, axis: 'x' | 'y'): number {
  const cs = window.getComputedStyle(container);
  if (axis === 'y') return Math.max(0, track.offsetTop + track.offsetHeight + (parseFloat(cs.paddingBottom) || 0) - container.clientHeight);
  return Math.max(0, track.offsetLeft + track.scrollWidth + (parseFloat(cs.paddingRight) || 0) - container.clientWidth);
}

export function scrollTo(el: HTMLElement, axis: 'x' | 'y', target: number, duration = SCROLL_MS): void {
  const track = trackOf(el);
  if (!track) {
    if (axis === 'y') el.scrollTop = Math.max(0, target); else el.scrollLeft = Math.max(0, target);
    return;
  }
  const o = offsets.get(el) || { x: 0, y: 0 };
  const to = Math.round(Math.max(0, Math.min(maxScroll(el, track, axis), target)));
  if (Math.abs(to - o[axis]) < 1) return;
  o[axis] = to;
  offsets.set(el, o);
  track.style.transition = duration > 0 ? `transform ${duration}ms ease-out` : 'none';
  track.style.transform = `translate3d(${-o.x}px, ${-o.y}px, 0)`;
}

/** Layout position of `child` inside `container`'s coordinate space (ignores running transforms). */
function layoutOffset(child: HTMLElement, container: HTMLElement): { x: number; y: number } | null {
  let x = 0;
  let y = 0;
  let e: HTMLElement | null = child;
  while (e && e !== container) {
    x += e.offsetLeft;
    y += e.offsetTop;
    const parent = e.offsetParent as HTMLElement | null;
    if (!parent) return null;
    if (parent === container) return { x, y };
    // offsetParent skipped the container (container not positioned): fall back to rects.
    if (!container.contains(parent)) return null;
    e = parent;
  }
  return { x, y };
}

/** Scrolls `container` so that `child` is visible with `pad` px of margin. */
export function ensureVisible(child: HTMLElement, container: HTMLElement, axis: 'x' | 'y', pad = 48): void {
  const track = trackOf(container);
  const pos = track ? layoutOffset(child, container) : null;
  if (track && pos) {
    const cur = getScroll(container, axis);
    const start = axis === 'y' ? pos.y : pos.x;
    const size = axis === 'y' ? child.offsetHeight : child.offsetWidth;
    const view = axis === 'y' ? container.clientHeight : container.clientWidth;
    if (start - pad < cur) scrollTo(container, axis, start - pad);
    else if (start + size + pad > cur + view) scrollTo(container, axis, start + size + pad - view);
    return;
  }
  const c = container.getBoundingClientRect();
  const r = child.getBoundingClientRect();
  const cur = getScroll(container, axis);
  if (axis === 'y') {
    if (r.top < c.top + pad) scrollTo(container, 'y', cur - (c.top + pad - r.top));
    else if (r.bottom > c.bottom - pad) scrollTo(container, 'y', cur + (r.bottom - (c.bottom - pad)));
  } else {
    if (r.left < c.left + pad) scrollTo(container, 'x', cur - (c.left + pad - r.left));
    else if (r.right > c.right - pad) scrollTo(container, 'x', cur + (r.right - (c.right - pad)));
  }
}

// ---------------------------------------------------------------------------------------------------------
// Zone: a handful of focusable elements ([data-focus]) navigated by geometry. Activation = element.click().

export interface ElementsZoneOptions {
  /** Scroll container to keep the focused element visible. */
  scroller?: HTMLElement | null;
  axis?: 'x' | 'y';
  pad?: number;
  /** Restrict moves to one axis (a row of cards: up/down leave the zone). */
  only?: 'x' | 'y';
  onFocus?: (el: HTMLElement) => void;
  remember?: boolean;
}

export class ElementsZone implements Zone {
  neighbors?: Partial<Record<Dir, Zone | (() => Zone | null)>>;
  private focused: HTMLElement | null = null;
  readonly el: HTMLElement;
  private readonly opts: ElementsZoneOptions;

  constructor(el: HTMLElement, opts: ElementsZoneOptions = {}) {
    this.el = el;
    this.opts = { remember: true, ...opts };
  }

  items(): HTMLElement[] {
    const list = this.el.querySelectorAll<HTMLElement>('[data-focus]');
    const out: HTMLElement[] = [];
    for (let i = 0; i < list.length; i++) {
      const e = list[i];
      if (e.offsetParent !== null && !e.hasAttribute('data-disabled')) out.push(e);
    }
    return out;
  }

  canFocus(): boolean { return this.items().length > 0; }

  get current(): HTMLElement | null { return this.focused; }

  enter(from: DOMRect | null, _dir: Dir | null): void {
    const items = this.items();
    if (!items.length) return;
    let target: HTMLElement | null = null;
    if (this.opts.remember && this.focused && items.indexOf(this.focused) >= 0 && !from) target = this.focused;
    if (!target && from) target = nearest(items, from);
    if (!target && this.opts.remember && this.focused && items.indexOf(this.focused) >= 0) target = this.focused;
    if (!target) target = items.filter((e) => e.hasAttribute('data-default'))[0] || items[0];
    this.set(target);
  }

  focusElement(el: HTMLElement): void { this.set(el); }

  pointerFocus(target: HTMLElement): boolean {
    const el = target.closest ? target.closest('[data-focus]') as HTMLElement | null : null;
    if (!el || !this.el.contains(el) || el.hasAttribute('data-disabled')) return false;
    if (this.focused !== el || !el.classList.contains('focused')) this.set(el, false);
    return true;
  }

  leave(): void { if (this.focused) this.focused.classList.remove('focused'); }

  move(dir: Dir): boolean {
    if (this.opts.only === 'x' && (dir === 'up' || dir === 'down')) return false;
    if (this.opts.only === 'y' && (dir === 'left' || dir === 'right')) return false;
    const items = this.items();
    if (!this.focused || items.indexOf(this.focused) < 0) { if (!items.length) return false; this.set(items[0]); return true; }
    const next = directional(items, this.focused, dir);
    if (!next) return false;
    this.set(next);
    return true;
  }

  activate(): void { if (this.focused) this.focused.click(); }

  focusedRect(): DOMRect | null { return this.focused ? this.focused.getBoundingClientRect() : null; }

  private set(el: HTMLElement, scroll = true): void {
    if (this.focused && this.focused !== el) this.focused.classList.remove('focused');
    this.focused = el;
    el.classList.add('focused');
    if (scroll && this.opts.scroller) ensureVisible(el, this.opts.scroller, this.opts.axis || 'y', this.opts.pad ?? 48);
    if (this.opts.onFocus) this.opts.onFocus(el);
  }
}

function center(r: DOMRect): [number, number] { return [r.left + r.width / 2, r.top + r.height / 2]; }

function nearest(items: HTMLElement[], from: DOMRect): HTMLElement {
  const [fx, fy] = center(from);
  let best = items[0];
  let bestD = Infinity;
  for (const e of items) {
    const [x, y] = center(e.getBoundingClientRect());
    const d = (x - fx) * (x - fx) + (y - fy) * (y - fy);
    if (d < bestD) { bestD = d; best = e; }
  }
  return best;
}

function directional(items: HTMLElement[], cur: HTMLElement, dir: Dir): HTMLElement | null {
  const from = cur.getBoundingClientRect();
  const [cx, cy] = center(from);
  let best: HTMLElement | null = null;
  let bestScore = Infinity;
  for (const e of items) {
    if (e === cur) continue;
    const r = e.getBoundingClientRect();
    const [x, y] = center(r);
    let primary: number;
    let secondary: number;
    if (dir === 'right') { if (r.left < from.right - 2 && x <= cx + 1) continue; primary = x - cx; secondary = Math.abs(y - cy); }
    else if (dir === 'left') { if (r.right > from.left + 2 && x >= cx - 1) continue; primary = cx - x; secondary = Math.abs(y - cy); }
    else if (dir === 'down') { if (r.top < from.bottom - 2 && y <= cy + 1) continue; primary = y - cy; secondary = Math.abs(x - cx); }
    else { if (r.bottom > from.top + 2 && y >= cy - 1) continue; primary = cy - y; secondary = Math.abs(x - cx); }
    if (primary <= 0) continue;
    // Same row/column first: overlap on the cross axis costs nothing.
    const overlap = dir === 'left' || dir === 'right'
      ? !(r.bottom < from.top || r.top > from.bottom)
      : !(r.right < from.left || r.left > from.right);
    const score = primary + (overlap ? 0 : secondary * 3 + 2000);
    if (score < bestScore) { bestScore = score; best = e; }
  }
  return best;
}

/** Index of the virtualised row (direct child of `inner`) that contains `target`, or -1. */
function rowIndexAt(target: HTMLElement, inner: HTMLElement, count: number): number {
  let e: HTMLElement | null = target;
  while (e && e.parentElement !== inner) e = e.parentElement;
  if (!e) return -1;
  const i = Number(e.getAttribute('data-vindex'));
  return i >= 0 && i < count ? i : -1;
}

// ---------------------------------------------------------------------------------------------------------
// Virtualised vertical list with fixed row height.

export interface VListOptions<T> {
  rowHeight: number;
  gap?: number;
  render: (item: T, index: number) => HTMLElement;
  onActivate?: (item: T, index: number) => void;
  onFocusChange?: (item: T, index: number) => void;
  /** Extra rows rendered above/below the viewport. */
  overscan?: number;
  padTop?: number;
  padBottom?: number;
}

export class VirtualList<T> implements Zone {
  neighbors?: Partial<Record<Dir, Zone | (() => Zone | null)>>;
  readonly el: HTMLElement;
  private readonly inner: HTMLElement;
  private items: T[] = [];
  index = 0;
  private hasFocus = false;
  private rendered = new Map<number, HTMLElement>();
  private readonly o: Required<Omit<VListOptions<T>, 'onActivate' | 'onFocusChange'>> & VListOptions<T>;

  constructor(cls: string, opts: VListOptions<T>) {
    this.o = { gap: 0, overscan: 4, padTop: 0, padBottom: 0, ...opts } as VirtualList<T>['o'];
    this.el = document.createElement('div');
    this.el.className = `vlist ${cls}`;
    this.inner = document.createElement('div');
    this.inner.className = 'vlist-inner track';
    this.el.appendChild(this.inner);
  }

  private get scrollY(): number { return getScroll(this.el, 'y'); }

  get stride(): number { return this.o.rowHeight + (this.o.gap || 0); }

  setItems(items: T[], keepIndex = false): void {
    this.items = items;
    if (!keepIndex) this.index = 0;
    this.index = Math.max(0, Math.min(this.index, items.length - 1));
    this.inner.style.height = `${(this.o.padTop || 0) + items.length * this.stride + (this.o.padBottom || 0)}px`;
    this.rendered.forEach((e) => e.remove());
    this.rendered.clear();
    if (!keepIndex) resetScroll(this.el);
    this.renderVisible();
  }

  /** Re-render visible rows (data inside changed, e.g. EPG arrived). */
  refresh(): void {
    this.rendered.forEach((e) => e.remove());
    this.rendered.clear();
    this.renderVisible();
  }

  get length(): number { return this.items.length; }
  get selected(): T | undefined { return this.items[this.index]; }

  canFocus(): boolean { return this.items.length > 0; }

  enter(from: DOMRect | null, _dir: Dir | null): void {
    this.hasFocus = true;
    if (from && this.items.length) {
      // Row whose vertical centre is closest to the previous focus.
      const r = this.el.getBoundingClientRect();
      const y = from.top + from.height / 2 - r.top + this.scrollY - (this.o.padTop || 0);
      const idx = Math.floor(y / this.stride);
      const visibleFirst = Math.floor(this.scrollY / this.stride);
      const visibleLast = Math.floor((this.scrollY + this.el.clientHeight) / this.stride) - 1;
      if (idx >= visibleFirst && idx <= visibleLast) this.index = Math.max(0, Math.min(this.items.length - 1, idx));
      else this.index = Math.max(0, Math.min(this.index, this.items.length - 1));
    }
    this.select(this.index, false);
  }

  leave(): void {
    this.hasFocus = false;
    this.rendered.forEach((e) => e.classList.remove('focused'));
  }

  move(dir: Dir): boolean {
    if (dir === 'up') { if (this.index <= 0) return false; this.select(this.index - 1, true); return true; }
    if (dir === 'down') { if (this.index >= this.items.length - 1) return false; this.select(this.index + 1, true); return true; }
    return false;
  }

  page(delta: number): void {
    const per = Math.max(1, Math.floor(this.el.clientHeight / this.stride) - 1);
    this.select(Math.max(0, Math.min(this.items.length - 1, this.index + delta * per)), true);
  }

  activate(): void {
    const it = this.items[this.index];
    if (it !== undefined && this.o.onActivate) this.o.onActivate(it, this.index);
  }

  pointerFocus(target: HTMLElement): boolean {
    const i = rowIndexAt(target, this.inner, this.items.length);
    if (i < 0) return false;
    if (this.hasFocus && i === this.index) return true;
    const prev = this.rendered.get(this.index);
    if (prev) prev.classList.remove('focused');
    this.hasFocus = true;
    this.index = i;
    const cur = this.rendered.get(i);
    if (cur) cur.classList.add('focused');
    if (this.o.onFocusChange) this.o.onFocusChange(this.items[i], i);
    return true;
  }

  pointerActivate(target: HTMLElement): boolean {
    if (!this.pointerFocus(target)) return false;
    this.activate();
    return true;
  }

  focusedRect(): DOMRect | null {
    const e = this.rendered.get(this.index);
    return e ? e.getBoundingClientRect() : null;
  }

  select(i: number, animate: boolean): void {
    if (!this.items.length) return;
    const prev = this.rendered.get(this.index);
    if (prev) prev.classList.remove('focused');
    this.index = i;
    this.scrollToIndex(i, animate);
    this.renderVisible();
    const cur = this.rendered.get(i);
    if (cur && this.hasFocus) cur.classList.add('focused');
    if (this.o.onFocusChange) this.o.onFocusChange(this.items[i], i);
  }

  scrollToIndex(i: number, animate: boolean): void {
    const top = (this.o.padTop || 0) + i * this.stride;
    const view = this.el.clientHeight;
    const pad = this.stride; // keep one row of context
    const cur = this.scrollY;
    let target = cur;
    if (top < target + pad) target = top - pad;
    else if (top + this.o.rowHeight > target + view - pad) target = top + this.o.rowHeight - view + pad;
    if (target !== cur) {
      this.prevScroll = cur;
      scrollTo(this.el, 'y', target, animate ? 140 : 0);
    }
  }

  /** Scroll position before the running animation: rows between old and new position must exist. */
  private prevScroll = 0;

  private renderVisible(): void {
    const n = this.items.length;
    if (!n) return;
    const view = this.el.clientHeight || 1080;
    const cur = this.scrollY;
    const lo = Math.min(cur, this.prevScroll);
    const hi = Math.max(cur, this.prevScroll);
    this.prevScroll = cur;
    const st = lo - (this.o.padTop || 0);
    const span = view + (hi - lo);
    const first = Math.max(0, Math.floor(st / this.stride) - (this.o.overscan || 0));
    const last = Math.min(n - 1, Math.ceil((st + span) / this.stride) + (this.o.overscan || 0));
    this.rendered.forEach((e, i) => {
      if (i < first || i > last) { e.remove(); this.rendered.delete(i); }
    });
    for (let i = first; i <= last; i++) {
      if (this.rendered.has(i)) continue;
      const e = this.o.render(this.items[i], i);
      e.style.position = 'absolute';
      e.style.left = '0';
      e.style.right = '0';
      e.style.top = `${(this.o.padTop || 0) + i * this.stride}px`;
      e.style.height = `${this.o.rowHeight}px`;
      e.setAttribute('data-vindex', String(i));
      if (i === this.index && this.hasFocus) e.classList.add('focused');
      this.inner.appendChild(e);
      this.rendered.set(i, e);
    }
  }
}

// ---------------------------------------------------------------------------------------------------------
// Virtualised grid with fixed cell size (posters).

export interface VGridOptions<T> {
  cellWidth: number;
  cellHeight: number;
  gapX: number;
  gapY: number;
  padX: number;
  padTop: number;
  render: (item: T, index: number) => HTMLElement;
  onActivate?: (item: T, index: number) => void;
}

export class VirtualGrid<T> implements Zone {
  neighbors?: Partial<Record<Dir, Zone | (() => Zone | null)>>;
  readonly el: HTMLElement;
  private readonly inner: HTMLElement;
  private items: T[] = [];
  index = 0;
  private cols = 1;
  private hasFocus = false;
  private rendered = new Map<number, HTMLElement>();
  private readonly o: VGridOptions<T>;

  constructor(cls: string, o: VGridOptions<T>) {
    this.o = o;
    this.el = document.createElement('div');
    this.el.className = `vgrid ${cls}`;
    this.inner = document.createElement('div');
    this.inner.className = 'vlist-inner track';
    this.el.appendChild(this.inner);
  }

  private get scrollY(): number { return getScroll(this.el, 'y'); }
  private prevScroll = 0;

  private get rowStride(): number { return this.o.cellHeight + this.o.gapY; }

  setItems(items: T[]): void {
    this.items = items;
    this.index = 0;
    const width = this.el.clientWidth || 1500;
    this.cols = Math.max(1, Math.floor((width - 2 * this.o.padX + this.o.gapX) / (this.o.cellWidth + this.o.gapX)));
    const rows = Math.ceil(items.length / this.cols);
    this.inner.style.height = `${this.o.padTop + rows * this.rowStride + 48}px`;
    this.rendered.forEach((e) => e.remove());
    this.rendered.clear();
    resetScroll(this.el);
    this.prevScroll = 0;
    this.renderVisible();
  }

  canFocus(): boolean { return this.items.length > 0; }

  enter(from: DOMRect | null, dir: Dir | null): void {
    this.hasFocus = true;
    if (from && this.items.length) {
      const r = this.el.getBoundingClientRect();
      const col = Math.max(0, Math.min(this.cols - 1, Math.floor((from.left + from.width / 2 - r.left - this.o.padX) / (this.o.cellWidth + this.o.gapX))));
      const firstRow = Math.ceil((this.scrollY - this.o.padTop) / this.rowStride);
      if (dir === 'down' || dir === null) this.index = Math.min(this.items.length - 1, Math.max(0, firstRow) * this.cols + col);
      else if (dir === 'left' || dir === 'right') {
        const y = from.top + from.height / 2 - r.top + this.scrollY - this.o.padTop;
        const row = Math.max(0, Math.floor(y / this.rowStride));
        this.index = Math.min(this.items.length - 1, row * this.cols + (dir === 'right' ? 0 : this.cols - 1));
      }
    }
    this.select(this.index, false);
  }

  leave(): void {
    this.hasFocus = false;
    this.rendered.forEach((e) => e.classList.remove('focused'));
  }

  move(dir: Dir): boolean {
    const i = this.index;
    const col = i % this.cols;
    if (dir === 'left') { if (col === 0) return false; this.select(i - 1, true); return true; }
    if (dir === 'right') { if (col === this.cols - 1 || i + 1 >= this.items.length) return false; this.select(i + 1, true); return true; }
    if (dir === 'up') { if (i - this.cols < 0) return false; this.select(i - this.cols, true); return true; }
    if (i + this.cols >= this.items.length) {
      // Last partial row: go to its last item if we're below the end.
      const lastRowStart = Math.floor((this.items.length - 1) / this.cols) * this.cols;
      if (i < lastRowStart) { this.select(this.items.length - 1, true); return true; }
      return false;
    }
    this.select(i + this.cols, true);
    return true;
  }

  activate(): void {
    const it = this.items[this.index];
    if (it !== undefined && this.o.onActivate) this.o.onActivate(it, this.index);
  }

  pointerFocus(target: HTMLElement): boolean {
    const i = rowIndexAt(target, this.inner, this.items.length);
    if (i < 0) return false;
    if (this.hasFocus && i === this.index) return true;
    const prev = this.rendered.get(this.index);
    if (prev) prev.classList.remove('focused');
    this.hasFocus = true;
    this.index = i;
    const cur = this.rendered.get(i);
    if (cur) cur.classList.add('focused');
    return true;
  }

  pointerActivate(target: HTMLElement): boolean {
    if (!this.pointerFocus(target)) return false;
    this.activate();
    return true;
  }

  focusedRect(): DOMRect | null {
    const e = this.rendered.get(this.index);
    return e ? e.getBoundingClientRect() : null;
  }

  private select(i: number, animate: boolean): void {
    if (!this.items.length) return;
    const prev = this.rendered.get(this.index);
    if (prev) prev.classList.remove('focused');
    this.index = Math.max(0, Math.min(this.items.length - 1, i));
    const row = Math.floor(this.index / this.cols);
    const top = this.o.padTop + row * this.rowStride;
    const view = this.el.clientHeight;
    const cur = this.scrollY;
    let target = cur;
    if (top < target + 24) target = top - 24;
    else if (top + this.o.cellHeight > target + view - 24) target = top + this.o.cellHeight - view + 24;
    if (target !== cur) { this.prevScroll = cur; scrollTo(this.el, 'y', target, animate ? 160 : 0); }
    this.renderVisible();
    const el = this.rendered.get(this.index);
    if (el && this.hasFocus) el.classList.add('focused');
  }

  private renderVisible(): void {
    const n = this.items.length;
    if (!n) return;
    const view = this.el.clientHeight || 1080;
    const cur = this.scrollY;
    const lo = Math.min(cur, this.prevScroll);
    const hi = Math.max(cur, this.prevScroll);
    this.prevScroll = cur;
    const st = lo - this.o.padTop;
    const firstRow = Math.max(0, Math.floor(st / this.rowStride) - 1);
    const lastRow = Math.ceil((st + view + (hi - lo)) / this.rowStride) + 1;
    const first = firstRow * this.cols;
    const last = Math.min(n - 1, (lastRow + 1) * this.cols - 1);
    this.rendered.forEach((e, i) => { if (i < first || i > last) { e.remove(); this.rendered.delete(i); } });
    for (let i = first; i <= last; i++) {
      if (this.rendered.has(i)) continue;
      const e = this.o.render(this.items[i], i);
      e.style.position = 'absolute';
      e.style.left = `${this.o.padX + (i % this.cols) * (this.o.cellWidth + this.o.gapX)}px`;
      e.style.top = `${this.o.padTop + Math.floor(i / this.cols) * this.rowStride}px`;
      e.style.width = `${this.o.cellWidth}px`;
      e.setAttribute('data-vindex', String(i));
      if (i === this.index && this.hasFocus) e.classList.add('focused');
      this.inner.appendChild(e);
      this.rendered.set(i, e);
    }
  }
}

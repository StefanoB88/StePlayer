import { ICON_PATHS } from './iconData.ts';

type Child = Node | string | number | null | undefined | false;
type Attrs = Record<string, string | number | boolean | null | undefined | ((e: Event) => void)> & { class?: string | null; style?: string | null };

/** Minimal element factory: h('div', { class: 'row' }, child, 'text'). */
export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs?: Attrs | null, ...children: (Child | Child[])[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag);
  if (attrs) {
    for (const key of Object.keys(attrs)) {
      const v = attrs[key];
      if (v == null || v === false) continue;
      if (typeof v === 'function') el.addEventListener(key.replace(/^on/, '').toLowerCase(), v as EventListener);
      else if (key === 'class') el.className = String(v);
      else if (key === 'style') el.setAttribute('style', String(v));
      else el.setAttribute(key, v === true ? '' : String(v));
    }
  }
  append(el, children);
  return el;
}

export function append(el: Node, children: (Child | Child[])[]): void {
  for (const c of children) {
    if (Array.isArray(c)) append(el, c);
    else if (c == null || c === false) continue;
    else el.appendChild(typeof c === 'object' ? c : document.createTextNode(String(c)));
  }
}

export function clear(el: Node): void {
  while (el.firstChild) el.removeChild(el.firstChild);
}

/** Inline Material icon (same "round" family as the Android app). */
export function icon(name: string, size = 48, cls = ''): HTMLElement {
  const span = document.createElement('span');
  span.className = `icon ${cls}`.trim();
  span.style.width = `${size}px`;
  span.style.height = `${size}px`;
  span.innerHTML = `<svg viewBox="0 0 24 24" width="${size}" height="${size}" fill="currentColor" aria-hidden="true">${ICON_PATHS[name] || ''}</svg>`;
  return span;
}

export function setText(el: HTMLElement, text: string): void {
  if (el.textContent !== text) el.textContent = text;
}

export function show(el: HTMLElement, visible: boolean): void {
  el.style.display = visible ? '' : 'none';
}

// Shared pieces of the movie/series pages (Android ui/vod/DetailCommon.kt).
import { h, icon } from '../dom.ts';
import { ElementsZone } from '../focus.ts';
import { artImage } from '../components.ts';

/** Backdrop with the bottom scrim (Android BackdropHeader). */
export function backdrop(image: string | null, title: string, height: number): HTMLElement {
  const art = artImage(image, title, { initials: false });
  art.style.position = 'absolute'; art.style.left = '0'; art.style.top = '0'; art.style.width = '100%'; art.style.height = '100%';
  return h('div', { style: `position:relative;height:${height}px;overflow:hidden` }, art,
    h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;background:linear-gradient(transparent, rgba(10,14,31,0.85) 70%, #0A0E1F)' }));
}

export function metaLine(label: string, value: string | null | undefined): HTMLElement | null {
  if (!value || !value.trim()) return null;
  return h('div', { class: 'row', style: 'align-items:flex-start;padding:6px 0' },
    h('span', { class: 't-body-m c2', style: 'white-space:nowrap;margin-right:16px' }, label),
    h('span', { class: 't-body-m clamp3' }, value));
}

/**
 * Back arrow floating over the artwork on a dark translucent circle; once the page is scrolled
 * (`setCollapsed(true)`) a solid bar with the title fades in, so the arrow never overlaps the title.
 */
export function detailTopBar(onBack: () => void) {
  const back = h('div', { class: 'iconbtn s110', 'data-focus': true, style: 'width:80px;height:80px;background:rgba(0,0,0,0.45);transition:background 150ms' }, icon('arrow_back', 44));
  back.addEventListener('click', onBack);
  const title = h('div', { class: 't-title-l ellipsis grow', style: 'margin-left:16px;opacity:0;transition:opacity 150ms' });
  const bg = h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;background:var(--bg);opacity:0;transition:opacity 150ms' });
  const row = h('div', { class: 'row', style: 'position:relative;height:128px;padding:0 24px' }, back, title);
  const el = h('div', { style: 'position:absolute;left:0;top:0;right:0;z-index:5' }, bg, row);
  const zone = new ElementsZone(row);
  return {
    el, zone,
    setTitle(t: string) { title.textContent = t; },
    setCollapsed(c: boolean) {
      bg.style.opacity = c ? '1' : '0';
      title.style.opacity = c ? '1' : '0';
      back.style.background = c ? 'transparent' : 'rgba(0,0,0,0.45)';
    },
  };
}

/**
 * TV header of the movie/series pages: backdrop with bottom + left scrims, poster on the left and, next to it,
 * a column (title, meta, buttons, plot...) filled by the caller. Everything above the fold on a 1080p screen.
 */
export function detailHero(image: string | null, poster: string | null, title: string, posterWidth: number, height: number): { el: HTMLElement; column: HTMLElement } {
  const bd = backdrop(image, title, 760);
  bd.style.position = 'absolute'; bd.style.left = '0'; bd.style.top = '0'; bd.style.right = '0';
  const leftScrim = h('div', { style: 'position:absolute;left:0;top:0;right:0;height:760px;background:linear-gradient(90deg, #0A0E1F, rgba(10,14,31,0.6) 45%, transparent 80%)' });
  const p = artImage(poster, title);
  p.style.paddingTop = '150%';
  const posterBox = h('div', { style: `width:${posterWidth}px;flex:0 0 auto;border-radius:28px;overflow:hidden;box-shadow:0 24px 64px rgba(0,0,0,0.5)` }, p);
  const column = h('div', { class: 'col grow', style: 'margin-left:56px;padding-top:96px;min-width:0' });
  const row = h('div', { class: 'row', style: 'position:absolute;left:56px;right:72px;top:200px;align-items:flex-start' }, posterBox, column);
  return { el: h('div', { style: `position:relative;height:${height}px` }, bd, leftScrim, row), column };
}

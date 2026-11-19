// Home (Android ui/home/Home.kt): hero carousel, top bar (list switcher + "Aggiorna"), expiry banner,
// rows "Continua a guardare", "Canali recenti", "Film aggiunti di recente", "Serie aggiornate di recente".
import type { ExpiryNotice, Movie, Series, WatchProgress } from '../../core/api.ts';
import { RECENT_CATEGORY } from '../../core/api.ts';
import { app } from '../app.ts';
import { h, icon, clear } from '../dom.ts';
import { ElementsZone, ensureVisible, makeTrack, resetScroll, type Zone } from '../focus.ts';
import { artImage, emptyState, iconButton, optionsDialog, posterCard, primaryButton, secondaryButton, sectionTitle, wideCard } from '../components.ts';
import { Format } from '../format.ts';
import { session } from '../session.ts';
import { actions } from '../actions.ts';
import { shell, type Tab, type TabId } from '../shell.ts';
import { syncDialog } from './syncDialog.ts';

interface HeroItem { sourceId: number; id: string; title: string; image: string | null; subtitle: string; isSeries: boolean }

export function homeTab(switchTab: (t: TabId) => void): Tab {
  const core = app().core;
  const scroller = h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden' });
  const content = makeTrack(scroller);
  const el = h('div', {}, scroller);
  let zones: Zone[] = [];
  let heroTimer = 0;
  let restartHero: () => void = () => undefined;
  let dismissed: string[] = [];
  let loadSeq = 0;
  let refreshing = false;
  let refreshIcon: HTMLElement | null = null;
  const unsubs: (() => void)[] = [];
  // While another screen (player, details) covers Home, changes only mark it dirty: re-rendering hidden rows
  // made the TV re-decode posters while a video was playing (the player saves progress every 10 s).
  let visible = true;
  let dirty = false;
  const reload = () => { if (visible) load(); else dirty = true; };

  const tab: Tab = {
    el,
    get zones() { return zones; },
    defaultZone: () => zones.filter((z) => z.canFocus() && (z as { hero?: boolean }).hero)[0] || zones.filter((z) => z.canFocus() && !(z as { top?: boolean }).top)[0] || zones[0] || null,
    onShow: () => { visible = true; if (dirty) { dirty = false; load(); } else restartHero(); },
    onHide: () => { visible = false; window.clearInterval(heroTimer); },
    destroy: () => { window.clearInterval(heroTimer); unsubs.forEach((u) => u()); },
  };

  function rowZone(row: HTMLElement, section: HTMLElement): ElementsZone {
    return new ElementsZone(row, {
      only: 'x', scroller: row, axis: 'x', pad: 32,
      onFocus: () => ensureVisible(section, scroller, 'y', 0),
    });
  }

  function topBar(transparent: boolean): { el: HTMLElement; zone: ElementsZone } {
    const bar = h('div', { class: 'topbar', style: `position:${transparent ? 'absolute' : 'relative'};left:0;right:0;top:0;padding:0 24px;${transparent ? 'background:linear-gradient(rgba(0,0,0,0.55),transparent);' : ''}z-index:3` },
      h('div', { class: 't-title-l', style: 'margin-left:8px' }, 'StePlayer'), h('div', { class: 'grow' }));
    if (session.sources.length > 1 && session.source) {
      bar.appendChild(secondaryButton(session.source.name, () => {
        optionsDialog('Cambia lista', session.sources.map((s) => ({ label: s.name, checked: s.id === session.sourceId })), async (i) => {
          const s = session.sources[i];
          await core.sources.setActive(s.id);
          await session.reload();
          core.sync.syncIfStale(s.id).catch(() => undefined);
        });
      }, 'swap_horiz'));
    }
    if (session.source) {
      refreshIcon = icon('refresh', 40);
      if (refreshing) refreshIcon.classList.add('spin');
      const btn = h('div', { class: 'btn secondary s106', 'data-focus': true, style: 'padding:20px 28px;background:rgba(28,33,48,0.85)' }, refreshIcon, 'Aggiorna');
      btn.addEventListener('click', () => refresh());
      bar.appendChild(btn);
    }
    const zone = new ElementsZone(bar, { only: 'x', onFocus: () => ensureVisible(bar, scroller, 'y', 0) });
    (zone as unknown as { top: boolean }).top = true;
    return { el: bar, zone };
  }

  async function refresh() {
    const id = session.sourceId;
    if (id < 0 || refreshing) return;
    refreshing = true;
    refreshIcon?.classList.add('spin');
    try {
      // Blocking popup with step and progress; on failure it shows the reason with "Riprova" / "Chiudi".
      await syncDialog(id, 'Lista aggiornata');
    } finally {
      refreshing = false;
      refreshIcon?.classList.remove('spin');
    }
  }

  function hero(items: HeroItem[]): { el: HTMLElement; zone: ElementsZone } {
    let index = 0;
    const imgBox = h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0' });
    const title = h('div', { class: 't-display clamp2' });
    const subtitle = h('div', { class: 't-body-m c2', style: 'margin-top:12px' });
    const buttons = h('div', { class: 'btns', style: 'margin-top:32px' });
    const dots = h('div', { class: 'row', style: 'margin-top:8px' });
    const info = h('div', { class: 'col', style: 'position:absolute;left:48px;bottom:40px;width:50%' }, title, subtitle, buttons, dots);
    const box = h('div', { style: 'position:relative;height:840px;overflow:hidden' },
      imgBox,
      h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;background:linear-gradient(transparent, rgba(10,14,31,0.85) 70%, #0A0E1F)' }),
      h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;background:linear-gradient(90deg, #0A0E1F, rgba(10,14,31,0.6) 40%, transparent 75%)' }),
      info);
    const zone = new ElementsZone(buttons, { only: 'x', onFocus: () => ensureVisible(box, scroller, 'y', 0) });
    (zone as unknown as { hero: boolean }).hero = true;
    const show = (i: number) => {
      index = i;
      const it = items[i];
      const art = artImage(it.image, it.title, { initials: false });
      art.style.position = 'absolute'; art.style.left = '0'; art.style.top = '0'; art.style.width = '100%'; art.style.height = '100%';
      art.style.opacity = '0'; art.style.transition = 'opacity 500ms'; art.style.willChange = 'opacity';
      imgBox.appendChild(art);
      window.setTimeout(() => { art.style.opacity = '1'; }, 30);
      window.setTimeout(() => { while (imgBox.childNodes.length > 1) imgBox.removeChild(imgBox.firstChild!); }, 600);
      title.textContent = it.title;
      subtitle.textContent = it.subtitle;
      const hadFocus = buttons.querySelector('.focused') !== null;
      clear(buttons);
      if (it.isSeries) {
        buttons.appendChild(primaryButton('Episodi', () => actions.openSeries(it.sourceId, it.id), 'video_library'));
      } else {
        buttons.appendChild(primaryButton('Guarda ora', async () => {
          const m = await core.catalog.movie(it.sourceId, it.id);
          if (m) actions.playMovie(m, 0);
        }, 'play_arrow'));
        buttons.appendChild(secondaryButton('Dettagli', () => actions.openMovie(it.sourceId, it.id), 'info'));
      }
      if (hadFocus) zone.enter(null, null);
      clear(dots);
      items.forEach((_, j) => dots.appendChild(h('div', { style: `height:8px;width:${j === i ? 44 : 16}px;border-radius:999px;margin-right:12px;background:${j === i ? 'var(--accent)' : 'rgba(255,255,255,0.3)'}` })));
    };
    show(0);
    restartHero = () => {
      window.clearInterval(heroTimer);
      if (items.length > 1) heroTimer = window.setInterval(() => show((index + 1) % items.length), 7000);
    };
    restartHero();
    return { el: box, zone };
  }

  function banner(n: ExpiryNotice): { el: HTMLElement; zone: ElementsZone } {
    const color = n.expired ? 'var(--live)' : 'var(--warning)';
    const bg = n.expired ? 'rgba(255,59,92,0.14)' : 'rgba(255,176,32,0.14)';
    const close = iconButton('close', 'Chiudi', () => { dismissed.push(`${n.sourceId}:${n.daysLeft}:${n.expired}`); load(); }, { tint: 'var(--text2)' });
    const row = h('div', { class: 'row', style: `margin:16px 32px;border-radius:28px;background:${bg};padding:12px 8px 12px 28px` },
      h('span', { style: `color:${color};margin-right:24px` }, icon('info', 40)),
      h('div', { class: 'grow' }, h('div', { class: 't-label-l', style: `color:${color}` }, n.sourceName), h('div', { class: 't-body-m' }, n.message)),
      close);
    return { el: row, zone: new ElementsZone(row, { onFocus: () => ensureVisible(row, scroller, 'y', 0) }) };
  }

  function section(titleText: string, cards: HTMLElement[], onMore?: () => void): { el: HTMLElement; zone: ElementsZone } {
    const row = h('div', { class: 'hrow' }, h('div', { class: 'track row-track' }, cards));
    const header = h('div', { class: 'row' }, sectionTitle(titleText));
    if (onMore) {
      header.appendChild(h('div', { class: 'grow' }));
      header.appendChild(h('div', { class: 't-headline-s c2', style: 'padding:0 48px' }, '›'));
    }
    const sec = h('div', {}, header, row);
    return { el: sec, zone: rowZone(row, sec) };
  }

  async function load() {
    const seq = ++loadSeq;
    const sid = session.sourceId;
    if (sid < 0) { renderEmpty(); return; }
    const [cw, ch, mv, se, notice] = await Promise.all([
      core.library.continueWatching(sid, 20).catch(() => [] as WatchProgress[]),
      core.library.recentChannels(sid, 15).catch(() => [] as WatchProgress[]),
      core.catalog.movies(sid, RECENT_CATEGORY, 30).catch(() => [] as Movie[]),
      core.catalog.series(sid, RECENT_CATEGORY, 30).catch(() => [] as Series[]),
      core.reminders.notice(sid).catch(() => null),
    ]);
    if (seq !== loadSeq) return;
    if (!cw.length && !ch.length && !mv.length && !se.length) { renderEmpty(); return; }
    const heroItems: HeroItem[] = [
      ...mv.filter((m) => m.poster).slice(0, 4).map((m) => ({
        sourceId: m.sourceId, id: m.id, title: m.name, image: m.poster, isSeries: false,
        subtitle: [m.year ? String(m.year) : null, Format.rating(m.rating) ? `★ ${Format.rating(m.rating)}` : null].filter(Boolean).join('  ·  '),
      })),
      ...se.filter((s) => s.cover).slice(0, 3).map((s) => ({
        sourceId: s.sourceId, id: s.id, title: s.name, image: s.cover, isSeries: true,
        subtitle: [s.year ? String(s.year) : null, s.genre].filter(Boolean).join('  ·  '),
      })),
    ];
    const focusedKey = currentFocusKey();
    clear(content); resetScroll(scroller);
    const nz: Zone[] = [];
    const wrap = h('div', { style: 'position:relative' });
    content.appendChild(wrap);
    if (heroItems.length) { const hr = hero(heroItems); wrap.appendChild(hr.el); nz.push(hr.zone); } else { wrap.appendChild(h('div', { style: 'height:128px' })); }
    const tb = topBar(true);
    wrap.appendChild(tb.el);
    nz.unshift(tb.zone);
    if (notice && dismissed.indexOf(`${notice.sourceId}:${notice.daysLeft}:${notice.expired}`) < 0) {
      const b = banner(notice); content.appendChild(b.el); nz.push(b.zone);
    }
    if (cw.length) {
      const s = section('Continua a guardare', cw.map((p) => wideCard({
        title: p.title, image: p.image, subtitle: p.subtitle, progress: p.durationMs > 0 ? p.positionMs / p.durationMs : 0,
        width: 600, onClick: () => actions.playProgress(p),
      })));
      content.appendChild(s.el); nz.push(s.zone);
    }
    if (ch.length) {
      const s = section('Canali recenti', ch.map((p) => wideCard({ title: p.title, image: p.image, live: true, fit: true, width: 440, onClick: () => actions.playProgress(p) })));
      content.appendChild(s.el); nz.push(s.zone);
    }
    if (mv.length) {
      const s = section('Film aggiunti di recente', mv.map((m) => posterCard({
        title: m.name, image: m.poster, subtitle: m.year ? String(m.year) : null, rating: Format.rating(m.rating), width: 300,
        onClick: () => actions.openMovie(m.sourceId, m.id),
      })), () => switchTab('movies'));
      content.appendChild(s.el); nz.push(s.zone);
    }
    if (se.length) {
      const s = section('Serie aggiornate di recente', se.map((x) => posterCard({
        title: x.name, image: x.cover, subtitle: x.year ? String(x.year) : null, rating: Format.rating(x.rating), width: 300,
        onClick: () => actions.openSeries(x.sourceId, x.id),
      })), () => switchTab('series'));
      content.appendChild(s.el); nz.push(s.zone);
    }
    content.appendChild(h('div', { style: 'height:48px' }));
    zones = nz;
    restoreFocus(focusedKey);
  }

  // Keep the focus on the same row (by index) across reloads.
  function currentFocusKey(): number {
    const sh = shell();
    if (!sh || !sh.scope.current) return -1;
    return zones.indexOf(sh.scope.current);
  }
  function restoreFocus(idx: number) {
    const sh = shell();
    if (!sh || sh.selected !== 'home') return;
    const wasInTab = idx >= 0;
    sh.refreshZones();
    if (wasInTab) {
      const z = zones[Math.min(idx, zones.length - 1)];
      if (z && z.canFocus()) sh.scope.focus(z);
    }
  }

  function renderEmpty() {
    window.clearInterval(heroTimer);
    clear(content); resetScroll(scroller);
    const tb = topBar(false);
    const action = primaryButton('Impostazioni', () => actions.openSettings());
    const empty = emptyState('video_library', 'La libreria è vuota. Sincronizza la lista dalle impostazioni.', action);
    empty.style.top = '128px';
    content.append(tb.el, empty);
    zones = [tb.zone, new ElementsZone(empty)];
    shell()?.refreshZones();
  }

  unsubs.push(core.subscribe('library', reload));
  unsubs.push(core.subscribe('catalog', reload));
  unsubs.push(session.onChange(reload));
  load();
  return tab;
}

// Live (Android ui/live/Live.kt): categories in a vertical rail on the left, channels with now/next on the right.
// Long-press OK on a channel toggles the favourite (Android: long click).
import type { Category, Channel, ChannelWithEpg } from '../../core/api.ts';
import { ALL_CATEGORY, FAVORITES_CATEGORY } from '../../core/api.ts';
import { app } from '../app.ts';
import { h, icon, clear, append } from '../dom.ts';
import { ElementsZone, VirtualList, type Zone } from '../focus.ts';
import { artImage, emptyState, iconButton, loadingBox, progressBar } from '../components.ts';
import { Format } from '../format.ts';
import { session } from '../session.ts';
import { actions } from '../actions.ts';
import { shell, type Tab } from '../shell.ts';
import type { KeyPress } from '../keys.ts';

interface Chip { id: string; label: string }

export function liveTab(): Tab {
  const core = app().core;
  let chips: Chip[] = [];
  let selected: string | null = null;
  let channels: ChannelWithEpg[] = [];
  let favorites = new Set<string>();
  let loadSeq = 0;
  let clock = 0;

  const guideBtn = iconButton('calendar_view_week', 'Guida TV', () => {
    if (session.sourceId >= 0 && selected) actions.openGuide(session.sourceId, selected);
  });
  const top = h('div', { class: 'topbar' }, h('div', { class: 'title t-headline grow' }, 'Live'), guideBtn);
  const topZone = new ElementsZone(top, { only: 'x' });

  const rail = new VirtualList<Chip>('cat-rail', {
    rowHeight: 84, gap: 4, padTop: 12, padBottom: 24,
    render: (c) => h('div', { class: `cat-item s103${c.id === selected ? ' selected' : ''}` },
      h('div', { class: 'bar' }), h('div', { class: 't-label-l clamp2' }, c.label)),
    onActivate: (c) => selectCategory(c.id),
  });
  rail.el.style.cssText = 'position:absolute;left:0;top:128px;width:480px;bottom:0;overflow:hidden;background:rgba(20,24,33,0.5)';

  const list = new VirtualList<ChannelWithEpg>('ch-list', {
    rowHeight: 168, gap: 12, padTop: 8, padBottom: 32, overscan: 3,
    render: (item) => channelRow(item, favorites.has(item.channel.id)),
    onActivate: (item) => actions.playChannel(item.channel, selected),
  });
  list.el.style.cssText = 'position:absolute;left:488px;top:128px;right:0;bottom:0;overflow:hidden;padding:0 24px 0 8px';
  const listHost = h('div', { style: 'position:absolute;left:488px;top:128px;right:0;bottom:0' });

  const el = h('div', {}, top, rail.el, list.el, listHost);
  rail.neighbors = { right: () => list.canFocus() ? list : null, up: topZone };
  list.neighbors = { left: rail, up: topZone };

  const listZone: Zone & { onKey?: (k: KeyPress) => boolean } = list;
  listZone.onKey = (k) => {
    if (k.key === 'enterLong' || k.key === 'red') { toggleFavorite(); return true; }
    if (k.key === 'chUp') { list.page(-1); return true; }
    if (k.key === 'chDown') { list.page(1); return true; }
    return false;
  };

  function channelRow(item: ChannelWithEpg, fav: boolean): HTMLElement {
    const ch = item.channel;
    const logo = h('div', { class: 'ch-logo' }, artImage(ch.logo, ch.name, { fit: true }));
    const nameLine = h('div', { class: 'row' },
      ch.num != null ? h('span', { class: 't-label-m c2', style: 'margin-right:16px' }, String(ch.num)) : null,
      h('span', { class: 't-title-s ellipsis', style: 'min-width:0' }, ch.name),
      ch.archiveDays > 0 ? h('span', { style: 'color:var(--accent2);margin-left:12px' }, icon('history', 28)) : null);
    const body = h('div', { class: 'col grow' }, nameLine);
    const now = item.now;
    if (now) {
      const t = Date.now();
      append(body, [
        h('div', { class: 't-body-s ellipsis', style: 'margin-top:4px;color:rgba(245,247,250,0.85)' }, now.title),
        h('div', { style: 'width:90%;margin-top:10px' }, progressBar((t - now.startAt) / Math.max(1, now.endAt - now.startAt), 'thin')),
        item.next ? h('div', { class: 't-body-s c2 ellipsis', style: 'margin-top:8px' }, `${Format.clock(item.next.startAt)}  ${item.next.title}`) : null]);
    } else {
      body.appendChild(h('div', { class: 't-body-s c2 ellipsis' }, 'Nessuna informazione sul programma'));
    }
    return h('div', { class: 'ch-row s102' }, logo, body, fav ? h('span', { style: 'color:var(--accent);margin-left:16px' }, icon('star', 36)) : null);
  }

  async function toggleFavorite() {
    const item = list.selected;
    if (!item) return;
    try {
      const fav = await core.library.toggleFavorite(item.channel.sourceId, 'LIVE', item.channel.id);
      if (fav) favorites.add(item.channel.id); else favorites.delete(item.channel.id);
      list.refresh();
      app().toast(fav ? 'Aggiunto ai preferiti' : 'Rimosso dai preferiti');
    } catch { /* ignore */ }
  }

  function selectCategory(id: string) {
    if (id === selected) { if (list.canFocus()) shell()?.scope.focus(list); return; }
    selected = id;
    rail.refresh();
    loadChannels(true);
  }

  async function loadCategories() {
    const sid = session.sourceId;
    if (sid < 0) { chips = []; rail.setItems([]); showEmpty(); return; }
    let cats: Category[] = [];
    try { cats = await core.catalog.categories(sid, 'LIVE'); } catch { /* empty */ }
    chips = [{ id: FAVORITES_CATEGORY, label: 'Preferiti' }, { id: ALL_CATEGORY, label: 'Tutti' }, ...cats.map((c) => ({ id: c.id, label: c.name }))];
    if (!selected || !chips.some((c) => c.id === selected)) selected = (chips[2] || chips[1]).id;
    rail.setItems(chips, false);
    const idx = chips.findIndex((c) => c.id === selected);
    rail.index = Math.max(0, idx);
    rail.scrollToIndex(rail.index, false);
    rail.refresh();
    await loadChannels(false);
  }

  async function loadChannels(focusList: boolean) {
    const seq = ++loadSeq;
    const sid = session.sourceId;
    if (sid < 0 || !selected) return;
    clear(listHost);
    listHost.appendChild(loadingBox());
    list.setItems([]);
    let chs: Channel[] = [];
    try { chs = await core.catalog.channels(sid, selected); } catch { /* empty */ }
    try { favorites = await core.library.favoriteIds(sid, 'LIVE'); } catch { /* keep */ }
    if (seq !== loadSeq) return;
    channels = chs.map((c) => ({ channel: c, now: null, next: null }));
    clear(listHost);
    if (!channels.length) {
      listHost.appendChild(emptyState('live_tv', 'Nessun canale in questa categoria'));
      listHost.style.display = '';
    } else {
      listHost.style.display = 'none';
    }
    list.setItems(channels);
    shell()?.refreshZones();
    if (focusList && list.canFocus()) shell()?.scope.focus(list);
    loadEpg(seq);
  }

  async function loadEpg(seq: number) {
    const sid = session.sourceId;
    if (!channels.length) return;
    try {
      const withEpg = await core.epg.nowNext(sid, channels.map((c) => c.channel));
      if (seq !== loadSeq) return;
      const map = new Map(withEpg.map((w) => [w.channel.id, w]));
      channels = channels.map((c) => map.get(c.channel.id) || c);
      list.setItems(channels, true);
    } catch { /* no guide */ }
  }

  function showEmpty() {
    clear(listHost);
    listHost.style.display = '';
    listHost.appendChild(emptyState('live_tv', 'Nessun canale in questa categoria'));
  }

  let visible = false;
  let dirty: 'catalog' | 'epg' | null = null;
  const unsubs = [
    session.onChange(() => { selected = null; loadCategories(); }),
    core.subscribe('catalog', () => { if (visible) loadCategories(); else dirty = 'catalog'; }),
    core.subscribe('epg', () => { if (visible) loadEpg(loadSeq); else if (!dirty) dirty = 'epg'; }),
  ];

  loadCategories();

  return {
    el,
    get zones() { return [topZone, rail, list] as Zone[]; },
    defaultZone: () => (list.canFocus() ? list : rail.canFocus() ? rail : null), // never the guide button while loading
    onShow: () => {
      visible = true;
      if (dirty === 'catalog') loadCategories(); else if (dirty === 'epg') loadEpg(loadSeq);
      dirty = null;
      window.clearInterval(clock);
      // One clock for the whole list: progress bars move without reloading.
      clock = window.setInterval(() => list.refresh(), 60_000);
    },
    onHide: () => { visible = false; window.clearInterval(clock); },
    destroy: () => { window.clearInterval(clock); unsubs.forEach((u) => u()); },
  };
}

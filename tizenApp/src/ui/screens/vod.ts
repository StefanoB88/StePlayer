// Film / Serie (Android ui/vod/Grids.kt): category chips + poster grid (virtualised: up to 5,000 posters).
import type { Category, ContentType } from '../../core/api.ts';
import { ALL_CATEGORY, FAVORITES_CATEGORY, RECENT_CATEGORY } from '../../core/api.ts';
import { app } from '../app.ts';
import { h, clear } from '../dom.ts';
import { ElementsZone, VirtualGrid, makeTrack, resetScroll, type Zone } from '../focus.ts';
import { chip, emptyState, loadingBox, posterCard } from '../components.ts';
import { Format } from '../format.ts';
import { session } from '../session.ts';
import { actions } from '../actions.ts';
import { shell, type Tab } from '../shell.ts';

interface GridItem { sourceId: number; id: string; title: string; image: string | null; subtitle: string | null; rating: string | null }

const CONTENT_WIDTH = 1920 - 184;
const PAD = 32;
const GAP_X = 28;
const COLS = Math.max(1, Math.floor((CONTENT_WIDTH - 2 * PAD + GAP_X) / (300 + GAP_X)));
const CELL_W = Math.floor((CONTENT_WIDTH - 2 * PAD - (COLS - 1) * GAP_X) / COLS);
const CELL_H = Math.round(CELL_W * 1.5) + 16 + 36 + 32;

export function vodTab(type: Extract<ContentType, 'MOVIE' | 'SERIES'>): Tab {
  const core = app().core;
  const isSeries = type === 'SERIES';
  let chips: { id: string; label: string }[] = [];
  let selected: string | null = null;
  let loadSeq = 0;

  const top = h('div', { class: 'topbar' }, h('div', { class: 'title t-headline' }, isSeries ? 'Serie' : 'Film'));
  const chipRow = h('div', { class: 'chips' });
  const chipTrack = makeTrack(chipRow, 'row-track');
  const chipZone = new ElementsZone(chipRow, { only: 'x', scroller: chipRow, axis: 'x', pad: 64 });
  const grid = new VirtualGrid<GridItem>('poster-grid', {
    cellWidth: CELL_W, cellHeight: CELL_H, gapX: GAP_X, gapY: 36, padX: PAD, padTop: 24,
    render: (it) => posterCard({ title: it.title, image: it.image, subtitle: it.subtitle, rating: it.rating, focusable: false }),
    onActivate: (it) => (isSeries ? actions.openSeries(it.sourceId, it.id) : actions.openMovie(it.sourceId, it.id)),
  });
  grid.el.style.cssText = 'position:absolute;left:0;right:0;top:216px;bottom:0;overflow:hidden';
  const host = h('div', { style: 'position:absolute;left:0;right:0;top:216px;bottom:0' });
  const header = h('div', { style: 'position:absolute;left:0;right:0;top:0' }, top, chipRow);
  const el = h('div', {}, header, grid.el, host);
  chipZone.neighbors = { down: () => (grid.canFocus() ? grid : null) };
  grid.neighbors = { up: chipZone };

  function renderChips() {
    clear(chipTrack);
    for (const c of chips) chipTrack.appendChild(chip(c.label, c.id === selected, () => select(c.id)));
  }

  function select(id: string) {
    if (id === selected) return;
    selected = id;
    const focused = chipRow.querySelector('.focused');
    const idx = focused ? Array.prototype.indexOf.call(chipTrack.children, focused) : -1;
    renderChips();
    if (idx >= 0) { const e = chipTrack.children[idx] as HTMLElement; if (e) chipZone.focusElement(e); }
    loadItems();
  }

  async function loadCategories() {
    const sid = session.sourceId;
    if (sid < 0) { chips = []; renderChips(); resetScroll(chipRow); grid.setItems([]); return; }
    let cats: Category[] = [];
    try { cats = await core.catalog.categories(sid, type); } catch { /* empty */ }
    chips = [
      { id: RECENT_CATEGORY, label: 'Recenti' },
      { id: FAVORITES_CATEGORY, label: 'Preferiti' },
      { id: ALL_CATEGORY, label: 'Tutti' },
      ...cats.map((c) => ({ id: c.id, label: c.name })),
    ];
    if (!selected || !chips.some((c) => c.id === selected)) selected = chips[0].id;
    renderChips();
    await loadItems();
  }

  async function loadItems() {
    const seq = ++loadSeq;
    const sid = session.sourceId;
    if (sid < 0 || !selected) return;
    clear(host);
    host.style.display = '';
    host.appendChild(loadingBox());
    grid.setItems([]);
    const limit = selected === RECENT_CATEGORY ? 120 : 5000;
    let items: GridItem[] = [];
    try {
      items = isSeries
        ? (await core.catalog.series(sid, selected, limit)).map((s) => ({ sourceId: s.sourceId, id: s.id, title: s.name, image: s.cover, subtitle: s.year ? String(s.year) : null, rating: Format.rating(s.rating) }))
        : (await core.catalog.movies(sid, selected, limit)).map((m) => ({ sourceId: m.sourceId, id: m.id, title: m.name, image: m.poster, subtitle: m.year ? String(m.year) : null, rating: Format.rating(m.rating) }));
    } catch { /* empty */ }
    if (seq !== loadSeq) return;
    clear(host);
    if (!items.length) host.appendChild(emptyState(isSeries ? 'video_library' : 'movie', isSeries ? 'Nessuna serie in questa categoria' : 'Nessun film in questa categoria'));
    else host.style.display = 'none';
    grid.setItems(items);
    shell()?.refreshZones();
  }

  let visible = false;
  let dirty = false;
  const unsubs = [
    session.onChange(() => { selected = null; loadCategories(); }),
    core.subscribe('catalog', () => { if (visible) loadCategories(); else dirty = true; }),
    core.subscribe('library', () => { if (selected === FAVORITES_CATEGORY) { if (visible) loadItems(); else dirty = true; } }),
  ];
  loadCategories();

  return {
    el,
    get zones() { return [chipZone, grid] as Zone[]; },
    defaultZone: () => (grid.canFocus() ? grid : chipZone),
    onShow: () => { visible = true; if (dirty) { dirty = false; loadCategories(); } },
    onHide: () => { visible = false; },
    destroy: () => unsubs.forEach((u) => u()),
  };
}

// TV guide (Android ui/live/Guide.kt): channels x time grid, 30 h window from 6 h ago, red "now" line.
// Rows are virtualised; inside a row only the programmes near the visible time range are built.
// D-pad: left/right = previous/next programme (left past the first = channel cell), up/down = same time on
// the adjacent channel. Titles of programmes that started before the visible area stick to its left edge.
import type { Channel, EpgProgram } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, icon, clear } from '../dom.ts';
import { ElementsZone, FocusScope, VirtualList, type Dir, type Zone } from '../focus.ts';
import { artImage, emptyState, loadingBox } from '../components.ts';
import { Format } from '../format.ts';
import { actions } from '../actions.ts';

const PER_MIN = 12; // px per minute (6 dp)
const ROW_H = 136; // 68 dp
const CH_COL = 348; // .g-ch width + margins (48 px on the left: overscan-safe)
const VIEW_W = 1920 - CH_COL;
const HALF_HOUR = 30 * 60_000;

export function guideScreen(sourceId: number, categoryId: string): Screen {
  const core = app().core;
  const now = Date.now();
  const from = Math.floor((now - 6 * 3_600_000) / HALF_HOUR) * HALF_HOUR;
  const to = from + 30 * 3_600_000;
  const totalW = ((to - from) / 60_000) * PER_MIN;
  const xOf = (t: number) => ((t - from) / 60_000) * PER_MIN;
  let scrollX = Math.max(0, xOf(now - HALF_HOUR));
  let channels: Channel[] = [];
  let programs: Record<string, EpgProgram[]> = {};
  let focusRow = 0;
  let focusTime = now;
  let onChannel = false;

  const back = h('div', { class: 'iconbtn s110', 'data-focus': true }, icon('arrow_back', 48));
  back.addEventListener('click', () => app().pop());
  const top = h('div', { class: 'topbar' }, back, h('div', { class: 'title t-title-l' }, 'Guida TV'));
  const topZone = new ElementsZone(top);
  const rulerTrack = h('div', { style: `position:absolute;left:0;top:0;height:72px;width:${totalW}px` });
  const ruler = h('div', { style: `position:absolute;left:${CH_COL}px;right:0;top:152px;height:72px;overflow:hidden` }, rulerTrack);
  const host = h('div', { style: 'position:absolute;left:0;right:0;top:224px;bottom:0' });
  const el = h('div', { class: 'screen' }, top, ruler, host);
  host.appendChild(loadingBox());

  for (let t = from; t < to; t += HALF_HOUR) {
    rulerTrack.appendChild(h('div', { class: 't-label-m c2', style: `position:absolute;left:${xOf(t) + 12}px;top:20px` }, Format.clock(t)));
  }
  rulerTrack.appendChild(h('div', { style: `position:absolute;left:${xOf(now)}px;top:0;width:4px;height:72px;background:var(--live)` }));

  const progsOf = (ch: Channel | undefined): EpgProgram[] => (ch && ch.epgId ? programs[ch.epgId] || [] : []);

  const list = new VirtualList<Channel>('guide-rows', {
    rowHeight: ROW_H, overscan: 2,
    render: (ch, i) => renderRow(ch, i),
  });
  list.el.style.cssText = 'position:absolute;left:0;right:0;top:0;bottom:0;overflow:hidden';

  function renderRow(ch: Channel, index: number): HTMLElement {
    const focusedRow = hasFocus && index === focusRow;
    const cell = h('div', { class: `g-ch${focusedRow && onChannel ? ' focused' : ''}` },
      h('div', { class: 'g-logo' }, artImage(ch.logo, ch.name, { fit: true })),
      h('div', { class: 't-label-m clamp2' }, ch.name));
    const track = h('div', { style: `position:absolute;left:0;top:0;bottom:0;width:${totalW}px;-webkit-transform:translateX(${-scrollX}px);transform:translateX(${-scrollX}px)` });
    const minX = scrollX - VIEW_W;
    const maxX = scrollX + VIEW_W * 2;
    const focusedProg = focusedRow && !onChannel ? programAt(progsOf(ch), focusTime) : null;
    for (const p of progsOf(ch)) {
      const start = Math.max(p.startAt, from);
      const x = xOf(start);
      const w = Math.max(16, xOf(p.endAt) - x - 6);
      if (x + w < minX || x > maxX) continue;
      const live = p.startAt <= now && now < p.endAt;
      const past = p.endAt <= now;
      // Sticky label: shift the text right by the part hidden on the left (keep ≥ 80 px visible).
      const hidden = Math.max(0, scrollX - x);
      const shift = Math.min(hidden, Math.max(0, w - 80));
      const block = h('div', { class: `g-prog${live ? ' live' : past ? ' past' : ''}${p === focusedProg ? ' focused' : ''}`, style: `left:${x}px;width:${w}px` },
        h('div', { style: `padding-left:${shift}px` },
          h('div', { class: 'row' },
            past && ch.archiveDays > 0 ? h('span', { style: 'color:var(--accent2);margin-right:8px' }, icon('history', 24)) : null,
            h('span', { class: `t-label-l ellipsis${past ? ' c2' : ''}` }, p.title)),
          h('div', { class: 't-body-s c2 ellipsis' }, `${Format.clock(p.startAt)} – ${Format.clock(p.endAt)}`)));
      track.appendChild(block);
    }
    track.appendChild(h('div', { style: `position:absolute;left:${xOf(now)}px;top:0;bottom:0;width:4px;background:var(--live)` }));
    return h('div', { class: 'g-row' }, cell, h('div', { class: 'g-progs' }, track));
  }

  function programAt(ps: EpgProgram[], t: number): EpgProgram | null {
    for (const p of ps) if (p.startAt <= t && t < p.endAt) return p;
    // Nearest programme after t, else before.
    let best: EpgProgram | null = null;
    for (const p of ps) if (p.endAt > from && (!best || Math.abs(p.startAt - t) < Math.abs(best.startAt - t))) best = p;
    return best;
  }

  function scrollToProgram(p: EpgProgram) {
    const x = xOf(Math.max(p.startAt, from));
    const w = xOf(p.endAt) - x;
    if (x < scrollX + 40) scrollX = Math.max(0, x - 40);
    // 80 px from the right edge of the screen: the focused programme stays inside the overscan-safe area.
    else if (x + Math.min(w, VIEW_W - 200) > scrollX + VIEW_W - 80) scrollX = Math.min(totalW - VIEW_W, x + Math.min(w, VIEW_W - 200) - VIEW_W + 80);
    rulerTrack.style.transform = `translateX(${-scrollX}px)`;
    (rulerTrack.style as unknown as Record<string, string>).webkitTransform = `translateX(${-scrollX}px)`;
  }

  let hasFocus = false;
  const zone: Zone = {
    el: list.el,
    canFocus: () => channels.length > 0,
    enter: () => { hasFocus = true; list.select(focusRow, false); list.refresh(); },
    leave: () => { hasFocus = false; list.refresh(); },
    move: (dir: Dir) => {
      const ps = progsOf(channels[focusRow]);
      if (dir === 'up' || dir === 'down') {
        const next = focusRow + (dir === 'up' ? -1 : 1);
        if (next < 0) return false;
        if (next >= channels.length) return true;
        focusRow = next;
        list.select(focusRow, true);
        if (!onChannel) { const p = programAt(progsOf(channels[focusRow]), focusTime); if (p) scrollToProgram(p); }
        list.refresh();
        return true;
      }
      if (onChannel) {
        if (dir === 'left') return false;
        onChannel = false;
        const p = programAt(ps, Math.max(focusTime, from + (scrollX / PER_MIN) * 60_000));
        if (p) { focusTime = Math.max(p.startAt, from) + 1; scrollToProgram(p); }
        list.refresh();
        return true;
      }
      const cur = programAt(ps, focusTime);
      const idx = cur ? ps.indexOf(cur) : -1;
      if (dir === 'left') {
        const prev = idx > 0 ? ps[idx - 1] : null;
        if (!prev || prev.endAt <= from) { onChannel = true; list.refresh(); return true; }
        focusTime = Math.max(prev.startAt, from) + 1;
        scrollToProgram(prev);
      } else {
        const next = idx >= 0 ? ps[idx + 1] : null;
        if (!next || next.startAt >= to) return true;
        focusTime = next.startAt + 1;
        scrollToProgram(next);
      }
      list.refresh();
      return true;
    },
    activate: () => {
      const ch = channels[focusRow];
      if (!ch) return;
      if (onChannel) { actions.playChannel(ch, categoryId); return; }
      const p = programAt(progsOf(ch), focusTime);
      if (!p) { actions.playChannel(ch, categoryId); return; }
      const t = Date.now();
      if (p.startAt <= t && t < p.endAt) actions.playChannel(ch, categoryId);
      else if (p.endAt <= t && ch.archiveDays > 0 && p.startAt >= t - ch.archiveDays * 86_400_000) actions.playCatchup(ch, p);
      else if (p.endAt <= t) app().toast('Replay non disponibile per questo canale');
      else app().toast(`${Format.dayTime(p.startAt)} · ${p.title}`);
    },
    focusedRect: () => list.el.getBoundingClientRect(),
    onKey: (k) => {
      if (k.key === 'chUp' || k.key === 'chDown') {
        list.page(k.key === 'chUp' ? -1 : 1);
        focusRow = list.index;
        list.refresh();
        return true;
      }
      return false;
    },
  };
  zone.neighbors = { up: topZone };
  const scope = new FocusScope([topZone, zone]);

  (async () => {
    try { channels = await core.catalog.channels(sourceId, categoryId); } catch { channels = []; }
    const ids = channels.map((c) => c.epgId).filter((x): x is string => !!x);
    const unique = ids.filter((x, i) => ids.indexOf(x) === i);
    try { programs = await core.epg.guide(sourceId, unique, from, to); } catch { programs = {}; }
    clear(host);
    if (!channels.length) { host.appendChild(emptyState('calendar_view_week', 'Nessun canale in questa categoria')); scope.ensure(); return; }
    host.appendChild(list.el);
    scrollToProgram({ channelEpgId: '', startAt: now - HALF_HOUR, endAt: now, title: '', description: null });
    list.setItems(channels);
    scope.focus(zone);
  })();

  return { el, scope };
}

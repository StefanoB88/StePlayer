// Global smart search (Android ui/search/Search.kt).
import type { Channel, Movie, ProgramHit, SearchHit, Series, SmartSearchResults } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, icon, clear } from '../dom.ts';
import { ElementsZone, FocusScope, ensureVisible, makeTrack, resetScroll, type Zone } from '../focus.ts';
import { artImage, emptyState, liveBadge, loadingBox, posterCard, progressBar, sectionTitle } from '../components.ts';
import { Format } from '../format.ts';
import { session } from '../session.ts';
import { actions } from '../actions.ts';
import { startEditing } from '../input.ts';

const DAYS = ['Dom', 'Lun', 'Mar', 'Mer', 'Gio', 'Ven', 'Sab'];

/** "Oggi 20:45", "Domani 15:00", "Sab 21:00". */
function dayLabel(ms: number): string {
  const d = new Date(ms);
  const today = new Date();
  const tomorrow = new Date(today.getTime() + 86_400_000);
  const same = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
  const time = Format.clock(ms);
  if (same(d, today)) return `Oggi ${time}`;
  if (same(d, tomorrow)) return `Domani ${time}`;
  return `${DAYS[d.getDay()]} ${time}`;
}

function norm(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** Bolds words whose accent-free form starts with one of the matched terms (Android highlight()). */
function highlight(text: string, terms: string[]): HTMLElement {
  const span = h('span', {});
  if (!terms.length) { span.textContent = text; return span; }
  const parts = text.split(/([^0-9A-Za-zÀ-ÿ]+)/);
  for (const part of parts) {
    if (!part) continue;
    const n = norm(part);
    const match = /[0-9A-Za-zÀ-ÿ]/.test(part[0]) ? terms.filter((t) => t && n.indexOf(t) === 0)[0] : undefined;
    if (match) {
      const len = Math.min(match.length, part.length);
      span.appendChild(h('b', { style: 'color:var(--accent2);font-weight:800' }, part.slice(0, len)));
      span.appendChild(document.createTextNode(part.slice(len)));
    } else span.appendChild(document.createTextNode(part));
  }
  return span;
}

export function searchScreen(): Screen {
  const core = app().core;
  let query = '';
  let timer = 0;
  let seq = 0;

  const input = h('input', { type: 'text', placeholder: 'Cerca canali, film, serie…', autocomplete: 'off', spellcheck: 'false',
    style: 'flex:1 1 auto;min-width:0;background:transparent;border:0;outline:none;color:var(--text);font-size:32px;font-family:inherit;margin-left:20px' });
  const clearBtn = h('span', { style: 'color:var(--text2);margin-left:16px;display:none' }, icon('close', 44));
  const field = h('div', { class: 'row s102', 'data-focus': true, 'data-default': true,
    style: 'flex:1 1 auto;height:104px;border-radius:999px;background:var(--surface);border:3px solid var(--outline);padding:0 36px;margin-right:16px' },
    h('span', { style: 'color:var(--text2)' }, icon('search', 48)), input, clearBtn);
  field.addEventListener('click', () => startEditing(input));
  const back = h('div', { class: 'iconbtn s110', 'data-focus': true }, icon('arrow_back', 48));
  back.addEventListener('click', () => app().pop());
  const top = h('div', { class: 'row', style: 'padding:16px;height:136px' }, back, h('div', { style: 'width:16px' }), field);
  const scroller = h('div', { style: 'position:absolute;left:0;right:0;top:136px;bottom:0;overflow:hidden' });
  const content = makeTrack(scroller);
  const el = h('div', { class: 'screen' }, top, scroller);
  const topZone = new ElementsZone(top, { only: 'x' });
  const scope = new FocusScope([topZone]);

  input.addEventListener('input', () => {
    query = input.value;
    clearBtn.style.display = query ? '' : 'none';
    window.clearTimeout(timer);
    timer = window.setTimeout(() => run(), 300);
  });
  input.addEventListener('keydown', (e) => { if (e.keyCode === 13 || e.keyCode === 65376) remember(); });

  const remember = () => { const q = query.trim(); if (q.length >= 2) core.search.rememberQuery(q).catch(() => undefined); };
  const open = {
    channel: (c: Channel) => { remember(); actions.playChannel(c, c.categoryId); },
    movie: (m: Movie) => { remember(); actions.openMovie(m.sourceId, m.id); },
    series: (s: Series) => { remember(); actions.openSeries(s.sourceId, s.id); },
    program: (hit: ProgramHit) => {
      remember();
      if (hit.status === 'REPLAY') actions.playCatchup(hit.channel, hit.program);
      else actions.playChannel(hit.channel, hit.channel.categoryId);
    },
  };

  function setZones(zs: Zone[]) {
    scope.zones = [topZone, ...zs];
    if (scope.current && scope.zones.indexOf(scope.current) < 0) scope.focus(topZone);
  }

  async function showRecent() {
    clear(content); resetScroll(scroller);
    let recent: string[] = [];
    try { recent = await core.search.recentQueries(); } catch { /* none */ }
    if (query.trim().length >= 2) return;
    if (!recent.length) { content.appendChild(emptyState('search', 'Digita almeno 2 caratteri')); setZones([]); return; }
    const clearAll = h('div', { class: 'textbtn s104', 'data-focus': true }, 'Cancella');
    clearAll.addEventListener('click', async () => { await core.search.clearRecentQueries(); showRecent(); });
    const header = h('div', { class: 'row', style: 'padding:16px 32px' }, h('div', { class: 't-title-l grow' }, 'Ricerche recenti'), clearAll);
    const chips = h('div', { class: 'row', style: 'flex-wrap:wrap;padding:8px 32px' });
    for (const q of recent) {
      const c = h('div', { class: 'chip row s104', 'data-focus': true, style: 'margin-bottom:16px;color:var(--text)' },
        h('span', { style: 'color:var(--text2);margin-right:12px' }, icon('history', 32)), q);
      c.addEventListener('click', () => { input.value = q; query = q; clearBtn.style.display = ''; run(); });
      chips.appendChild(c);
    }
    content.append(header, chips);
    setZones([new ElementsZone(header), new ElementsZone(chips)]);
  }

  async function run() {
    const q = query.trim();
    const my = ++seq;
    if (q.length < 2 || session.sourceId < 0) { showRecent(); return; }
    clear(content); resetScroll(scroller);
    content.appendChild(loadingBox());
    let r: SmartSearchResults | null = null;
    try { r = await core.search.search(session.sourceId, q); } catch { r = null; }
    if (my !== seq) return;
    render(r, q);
  }

  function section(title: string, body: HTMLElement, horizontal: boolean, zs: Zone[]) {
    if (horizontal) makeTrack(body, 'row-track');
    const sec = h('div', {}, sectionTitle(title), body);
    content.appendChild(sec);
    zs.push(new ElementsZone(body, horizontal
      ? { only: 'x', scroller: body, axis: 'x', pad: 32, onFocus: () => ensureVisible(sec, scroller, 'y', 0) }
      : { scroller, axis: 'y', pad: 120 }));
  }

  function logoBox(url: string | null, name: string): HTMLElement {
    const a = artImage(url, name, { fit: true });
    a.style.cssText += ';position:absolute;left:8px;top:8px;right:8px;bottom:8px;background:transparent';
    return h('div', { style: 'position:relative;width:128px;height:88px;border-radius:16px;background:var(--surface2);overflow:hidden;margin-right:24px;flex:0 0 auto' }, a);
  }

  function programRow(hit: ProgramHit, terms: string[]): HTMLElement {
    const p = hit.program;
    const whenLabel = hit.status === 'LIVE' ? `${Format.clock(p.startAt)} – ${Format.clock(p.endAt)}` : dayLabel(p.startAt);
    const extra = hit.otherAirings > 0 ? `  ·  +${hit.otherAirings} repliche` : '';
    const title = h('div', { class: 'row' }, hit.status === 'LIVE' ? h('span', { style: 'margin-right:16px' }, liveBadge()) : null,
      h('span', { class: 't-title-s ellipsis' }, highlight(p.title, terms)));
    const body = h('div', { class: 'col grow' }, title,
      h('div', { class: 't-body-s c2 ellipsis', style: 'margin-top:4px' }, `${hit.channel.name}  ·  ${whenLabel}${extra}`));
    if (hit.status === 'LIVE') {
      const bar = progressBar((Date.now() - p.startAt) / Math.max(1, p.endAt - p.startAt), 'thin');
      bar.style.width = '90%'; bar.style.marginTop = '12px';
      body.appendChild(bar);
    }
    const row = h('div', { class: 'row s102', 'data-focus': true, style: 'background:var(--surface);border-radius:28px;padding:20px;margin:6px 24px' },
      logoBox(hit.channel.logo, hit.channel.name), body);
    row.addEventListener('click', () => open.program(hit));
    return row;
  }

  function tileCard(logo: string | null, raw: string, terms: string[], subtitle: string, live: boolean, onClick: () => void): HTMLElement {
    const a = artImage(logo, raw, { fit: true });
    a.style.cssText += ';position:absolute;left:56px;top:56px;right:56px;bottom:56px;background:transparent;padding-top:0';
    const box = h('div', { style: 'position:relative;width:100%;height:420px;border-radius:24px;background:var(--surface);overflow:hidden' }, a,
      live ? h('div', { style: 'position:absolute;left:16px;top:16px' }, liveBadge()) : null);
    const card = h('div', { class: 'poster', 'data-focus': true, style: 'width:448px' }, box,
      h('div', { class: 'meta' }, h('div', { class: 't-title-s ellipsis' }, highlight(raw, terms)), h('div', { class: 't-body-s c2 ellipsis' }, subtitle)));
    card.addEventListener('click', onClick);
    return card;
  }

  function topHit(hit: SearchHit, terms: string[]): HTMLElement {
    switch (hit.kind) {
      case 'movie': return posterCard({ title: hit.movie.name, image: hit.movie.poster, subtitle: 'Film', width: 280, onClick: () => open.movie(hit.movie) });
      case 'series': return posterCard({ title: hit.series.name, image: hit.series.cover, subtitle: 'Serie', width: 280, onClick: () => open.series(hit.series) });
      case 'channel': return tileCard(hit.channel.logo, hit.channel.name, terms, 'Canali', false, () => open.channel(hit.channel));
      default: {
        const sub = hit.status === 'LIVE' ? hit.channel.name : hit.status === 'UPCOMING' ? `${hit.channel.name} · ${dayLabel(hit.program.startAt)}` : `Replay · ${hit.channel.name}`;
        return tileCard(hit.channel.logo, hit.program.title, terms, sub, hit.status === 'LIVE', () => open.program(hit));
      }
    }
  }

  function render(r: SmartSearchResults | null, q: string) {
    clear(content); resetScroll(scroller);
    const empty = !r || (!r.liveNow.length && !r.upcoming.length && !r.replay.length && !r.channels.length && !r.movies.length && !r.series.length);
    if (!r || empty) {
      content.appendChild(emptyState('search', `Nessun risultato per «${q}». Prova con un'altra parola o controlla l'ortografia.`));
      setZones([]);
      return;
    }
    const zs: Zone[] = [];
    const terms = r.terms;
    if (r.correctedQuery) {
      content.appendChild(h('div', { class: 'col', style: 'margin:8px 32px;border-radius:24px;background:rgba(124,92,255,0.12);padding:24px' },
        h('div', { class: 't-label-l' }, `Risultati per «${r.correctedQuery}»`),
        h('div', { class: 't-body-s c2' }, `Forse cercavi: ${r.correctedQuery}`)));
    }
    if (r.topHits.length) section('Migliori risultati', h('div', { class: 'hrow' }, r.topHits.map((x) => topHit(x, terms))), true, zs);
    const progs = (title: string, hits: ProgramHit[]) => { if (hits.length) section(title, h('div', { class: 'col' }, hits.map((x) => programRow(x, terms))), false, zs); };
    progs('In onda ora', r.liveNow);
    progs('Prossimamente', r.upcoming);
    progs('Replay', r.replay);
    if (r.channels.length) {
      section('Canali', h('div', { class: 'col' }, r.channels.map((hit) => {
        const row = h('div', { class: 'row s102', 'data-focus': true, style: 'background:var(--surface);border-radius:28px;padding:20px;margin:6px 24px' },
          logoBox(hit.channel.logo, hit.channel.name), h('div', { class: 't-title-s ellipsis' }, highlight(hit.channel.name, terms)));
        row.addEventListener('click', () => open.channel(hit.channel));
        return row;
      })), false, zs);
    }
    if (r.movies.length) {
      section('Film', h('div', { class: 'hrow' }, r.movies.map((x) => posterCard({
        title: x.movie.name, image: x.movie.poster, subtitle: x.movie.year ? String(x.movie.year) : null, rating: Format.rating(x.movie.rating), width: 280,
        onClick: () => open.movie(x.movie),
      }))), true, zs);
    }
    if (r.series.length) {
      section('Serie', h('div', { class: 'hrow' }, r.series.map((x) => posterCard({
        title: x.series.name, image: x.series.cover, subtitle: x.matchedInPlot ? 'nella trama' : x.series.year ? String(x.series.year) : null,
        rating: Format.rating(x.series.rating), width: 280, onClick: () => open.series(x.series),
      }))), true, zs);
    }
    content.appendChild(h('div', { style: 'height:64px' }));
    setZones(zs);
  }

  showRecent();
  return {
    el, scope,
    onShow: () => { if (!query) window.setTimeout(() => { scope.focus(topZone); topZone.focusElement(field); }, 0); },
  };
}

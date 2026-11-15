// Series detail (Android ui/vod/SeriesDetail.kt): header, "Riproduci · S1 E2" (resume / next episode),
// favourite, season chips, episode list with progress.
import type { Episode, Series, SeriesDetail, WatchProgress } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, clear, append } from '../dom.ts';
import { ElementsZone, FocusScope, ensureVisible, getScroll, makeTrack, resetScroll } from '../focus.ts';
import { artImage, chip, emptyState, loadingBox, primaryButton, progressBar, secondaryButton, textDialog } from '../components.ts';
import { Format } from '../format.ts';
import { actions } from '../actions.ts';
import { detailHero, detailTopBar, metaLine } from './detailCommon.ts';

export function seriesScreen(sourceId: number, id: string): Screen {
  const core = app().core;
  const scroller = h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden' });
  const content = makeTrack(scroller);
  const topBar = detailTopBar(() => app().pop());
  const el = h('div', { class: 'screen' }, scroller, topBar.el);
  const scope = new FocusScope();
  let series: Series | null = null;
  let detail: SeriesDetail | null = null;
  let loadingEpisodes = true;
  let season: number | null = null;
  let progress = new Map<string, WatchProgress>();
  let favorite = false;
  let zones: { buttons?: ElementsZone; seasons?: ElementsZone; episodes?: ElementsZone } = {};

  content.appendChild(loadingBox());

  const seasons = () => (detail ? detail.seasons : []);
  const selectedSeason = () => (season != null && seasons().indexOf(season) >= 0 ? season : seasons()[0] ?? null);
  const episodes = (): Episode[] => {
    const s = selectedSeason();
    return detail && s != null ? detail.episodes.filter((e) => e.season === s) : [];
  };
  /** Resume the last watched, else the next, else the first (Android SeriesUi.upNext). */
  const upNext = (): [Episode, number] | null => {
    const all = detail ? detail.episodes : [];
    if (!all.length) return null;
    let last: WatchProgress | null = null;
    progress.forEach((p) => { if (!last || p.updatedAt > last.updatedAt) last = p; });
    if (!last) return [all[0], 0];
    const lp = last as WatchProgress;
    const idx = all.findIndex((e) => e.id === lp.itemId);
    if (idx < 0) return [all[0], 0];
    const frac = lp.durationMs > 0 ? lp.positionMs / lp.durationMs : 0;
    return frac < 0.95 ? [all[idx], lp.positionMs] : [all[idx + 1] || all[idx], 0];
  };
  const play = (ep: Episode, start: number) => {
    if (!detail) return;
    actions.playEpisode(detail.series, ep, detail.episodes, start);
  };

  function render() {
    const s = series;
    if (!s) return;
    const focusedPart = scope.current === zones.episodes ? 'episodes' : scope.current === zones.seasons ? 'seasons' : 'buttons';
    const focusedIndex = focusedPart === 'episodes' && zones.episodes && zones.episodes.current
      ? Array.prototype.indexOf.call(zones.episodes.el.children, zones.episodes.current) : -1;
    clear(content);
    topBar.setTitle(s.name);
    const meta = [s.year ? String(s.year) : null, Format.rating(s.rating) ? `★ ${Format.rating(s.rating)}` : null, s.genre].filter(Boolean).join('   ·   ');
    const hero = detailHero((detail && detail.backdrop) || s.cover, s.cover, s.name, 320, 820);
    const col = hero.column;
    col.appendChild(h('div', { class: 't-headline clamp2' }, s.name));
    if (meta) col.appendChild(h('div', { class: 't-body-m c2', style: 'margin-top:12px' }, meta));
    const buttons = h('div', { class: 'btns', style: 'margin-top:32px' });
    const next = upNext();
    if (next) {
      const [ep, pos] = next;
      const label = `S${ep.season} E${ep.number}`;
      buttons.appendChild(primaryButton(pos > 30_000 ? `Riprendi da ${Format.duration(pos)}  ·  ${label}` : `Riproduci  ·  ${label}`, () => play(ep, pos), 'play_arrow'));
    }
    buttons.appendChild(secondaryButton('', () => toggleFavorite(), favorite ? 'favorite' : 'favorite_border', { tint: favorite ? 'var(--live)' : undefined }));
    if (s.plot || (detail && detail.cast)) {
      buttons.appendChild(secondaryButton('Trama completa', () => textDialog(s.name, [
        s.plot, s.genre ? `Genere: ${s.genre}` : null, detail && detail.director ? `Regia: ${detail.director}` : null,
        detail && detail.cast ? `Cast: ${detail.cast}` : null,
      ]), 'notes'));
    }
    col.appendChild(buttons);
    if (s.plot) col.appendChild(h('div', { class: 't-body-l', style: 'display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden;width:92%;margin:8px 0 16px;color:rgba(245,247,250,0.9)' }, s.plot));
    append(col, [metaLine('Cast', detail && detail.cast), metaLine('Regia', detail && detail.director)]);
    content.appendChild(hero.el);

    let seasonRow: HTMLElement | null = null;
    if (seasons().length > 1) {
      seasonRow = h('div', { class: 'chips', style: 'padding:32px 32px 16px' });
      for (const n of seasons()) seasonRow.appendChild(chip(`Stagione ${n}`, n === selectedSeason(), () => { season = n; render(); scope.focus(zones.seasons || null); }));
      content.appendChild(seasonRow);
    }
    const list = h('div', { class: 'col', style: 'padding:16px 32px 64px' });
    if (loadingEpisodes) list.appendChild(h('div', { class: 'row', style: 'justify-content:center;padding:64px' }, h('div', { class: 'spinner' })));
    else if (!episodes().length) list.appendChild(h('div', { class: 't-body-l c2', style: 'padding:48px 16px' }, 'Nessun episodio disponibile'));
    else for (const ep of episodes()) list.appendChild(episodeRow(ep));
    content.appendChild(list);

    const onFocusTop = () => { resetScroll(scroller); topBar.setCollapsed(false); };
    zones = {
      buttons: new ElementsZone(buttons, { only: 'x', onFocus: onFocusTop }),
      seasons: seasonRow ? new ElementsZone(seasonRow, { only: 'x', scroller: seasonRow, axis: 'x', pad: 64, onFocus: (e) => { ensureVisible(e, scroller, 'y', 160); topBar.setCollapsed(getScroll(scroller, 'y') > 400); } }) : undefined,
      episodes: list.querySelector('[data-focus]') ? new ElementsZone(list, {
        scroller, axis: 'y', pad: 160, onFocus: () => topBar.setCollapsed(getScroll(scroller, 'y') > 400),
      }) : undefined,
    };
    zones.buttons!.neighbors = { up: topBar.zone };
    scope.zones = [topBar.zone, zones.buttons!, ...(zones.seasons ? [zones.seasons] : []), ...(zones.episodes ? [zones.episodes] : [])];
    const target = focusedPart === 'episodes' ? zones.episodes : focusedPart === 'seasons' ? zones.seasons : zones.buttons;
    if (!scope.current || scope.zones.indexOf(scope.current) < 0 || target) {
      scope.focus(target || zones.buttons!);
      if (target === zones.episodes && zones.episodes && focusedIndex >= 0) {
        const e = zones.episodes.el.children[focusedIndex] as HTMLElement | undefined;
        if (e) zones.episodes.focusElement(e);
      }
    }
  }

  function episodeRow(ep: Episode): HTMLElement {
    const p = progress.get(ep.id);
    const thumb = artImage(ep.image, ep.title);
    thumb.style.cssText += ';position:absolute;left:0;top:0;width:100%;height:100%';
    const frac = p && p.durationMs > 0 ? p.positionMs / p.durationMs : 0;
    const t = h('div', { style: 'position:relative;width:280px;height:158px;border-radius:20px;overflow:hidden;flex:0 0 auto;margin-right:24px' }, thumb,
      frac > 0 ? h('div', { style: 'position:absolute;left:12px;right:12px;bottom:12px' }, progressBar(frac, 'thin')) : null);
    const row = h('div', { class: 'row s102', 'data-focus': true, style: 'background:var(--surface);border-radius:28px;padding:20px;margin:10px 0' }, t,
      h('div', { class: 'col grow' },
        h('div', { class: 't-title-s clamp2' }, `${ep.number}. ${ep.title}`),
        Format.minutes(ep.durationSec) ? h('div', { class: 't-body-s c2' }, Format.minutes(ep.durationSec) || '') : null,
        ep.plot ? h('div', { class: 't-body-s c2 clamp2', style: 'margin-top:4px' }, ep.plot) : null));
    row.addEventListener('click', () => {
      const pp = progress.get(ep.id);
      play(ep, pp && pp.durationMs > 0 && pp.positionMs / pp.durationMs < 0.95 ? pp.positionMs : 0);
    });
    return row;
  }

  async function toggleFavorite() {
    favorite = await core.library.toggleFavorite(sourceId, 'SERIES', id);
    render();
  }

  async function loadProgress() {
    progress = await core.library.seriesProgress(sourceId, id).catch(() => new Map<string, WatchProgress>());
    render();
  }

  (async () => {
    const s = await core.catalog.seriesById(sourceId, id).catch(() => null);
    if (!s) {
      clear(content);
      content.appendChild(emptyState('video_library', 'Nessuna serie in questa categoria'));
      scope.zones = [topBar.zone];
      scope.ensure();
      return;
    }
    series = s;
    favorite = await core.library.isFavorite(sourceId, 'SERIES', id).catch(() => false);
    progress = await core.library.seriesProgress(sourceId, id).catch(() => new Map<string, WatchProgress>());
    render();
    try {
      detail = await core.catalog.seriesDetail(s);
      series = detail.series;
    } catch { /* keep basic */ }
    loadingEpisodes = false;
    render();
  })();

  let visible = true;
  const unsub = core.subscribe('library', () => { if (visible) loadProgress(); });
  return { el, scope, onShow: () => { visible = true; loadProgress(); }, onHide: () => { visible = false; }, destroy: unsub };
}

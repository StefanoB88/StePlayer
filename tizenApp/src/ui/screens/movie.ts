// Movie detail (Android ui/vod/MovieDetail.kt).
import type { MovieDetail, WatchProgress } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, clear, append } from '../dom.ts';
import { ElementsZone, FocusScope } from '../focus.ts';
import { emptyState, loadingBox, primaryButton, progressBar, secondaryButton, textDialog } from '../components.ts';
import { Format } from '../format.ts';
import { actions } from '../actions.ts';
import { detailHero, detailTopBar, metaLine } from './detailCommon.ts';

export function movieScreen(sourceId: number, id: string): Screen {
  const core = app().core;
  const body = h('div', { style: 'position:absolute;left:0;top:0;right:0;bottom:0;overflow:hidden' });
  const topBar = detailTopBar(() => app().pop());
  const el = h('div', { class: 'screen' }, body, topBar.el);
  const scope = new FocusScope();
  let detail: MovieDetail | null = null;
  let progress: WatchProgress | null = null;
  let favorite = false;
  let buttonsZone: ElementsZone | null = null;

  body.appendChild(loadingBox());

  function render() {
    const d = detail;
    if (!d) return;
    const m = d.movie;
    const keepFocus = scope.current === buttonsZone;
    // Re-rendering (e.g. after toggling the favourite) keeps the focus on the same button, not on the first one.
    const keepIndex = keepFocus && buttonsZone ? buttonsZone.items().indexOf(buttonsZone.current as HTMLElement) : -1;
    clear(body);
    topBar.setTitle(m.name);
    const meta = [m.year ? String(m.year) : null, Format.minutes(d.durationSec),
      Format.rating(m.rating) ? `★ ${Format.rating(m.rating)}` : null, m.extension ? m.extension.toUpperCase() : null].filter(Boolean).join('   ·   ');
    const hero = detailHero(d.backdrop || m.poster, m.poster, m.name, 360, 1080);
    const col = hero.column;
    col.appendChild(h('div', { class: 't-headline clamp2' }, m.name));
    if (meta) col.appendChild(h('div', { class: 't-body-m c2', style: 'margin-top:12px' }, meta));
    const canResume = !!progress && progress.positionMs > 30_000 && progress.durationMs > 0 && progress.positionMs / progress.durationMs < 0.95;
    if (canResume && progress) {
      const bar = progressBar(progress.positionMs / progress.durationMs);
      bar.style.width = '40%'; bar.style.marginTop = '28px';
      col.appendChild(bar);
    }
    const buttons = h('div', { class: 'btns', style: 'margin-top:32px' });
    if (canResume && progress) {
      const p = progress;
      buttons.append(
        primaryButton(`Riprendi da ${Format.duration(p.positionMs)}`, () => actions.playMovie(m, p.positionMs), 'play_arrow'),
        secondaryButton("Dall'inizio", () => actions.playMovie(m, 0), 'replay'));
    } else {
      buttons.appendChild(primaryButton('Riproduci', () => actions.playMovie(m, 0), 'play_arrow'));
    }
    buttons.appendChild(secondaryButton('', () => toggleFavorite(), favorite ? 'favorite' : 'favorite_border', { tint: favorite ? 'var(--live)' : undefined }));
    if (d.plot || d.cast) {
      buttons.appendChild(secondaryButton('Trama completa', () => textDialog(m.name, [
        d.plot, d.genre ? `Genere: ${d.genre}` : null, d.director ? `Regia: ${d.director}` : null, d.cast ? `Cast: ${d.cast}` : null,
      ]), 'notes'));
    }
    col.appendChild(buttons);
    // The page doesn't scroll: texts are cut so that everything ends above the bottom safe margin (the full
    // texts are in "Trama completa").
    if (d.plot) col.appendChild(h('div', { class: 't-body-l', style: 'display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden;width:92%;margin:8px 0 12px;color:rgba(245,247,250,0.9)' }, d.plot));
    append(col, [metaLine('Genere', d.genre, 1), metaLine('Regia', d.director, 1), metaLine('Cast', d.cast, 2)]);
    body.appendChild(hero.el);
    buttonsZone = new ElementsZone(buttons, { only: 'x' });
    buttonsZone.neighbors = { up: topBar.zone };
    scope.zones = [topBar.zone, buttonsZone];
    if (keepFocus || !scope.current) {
      scope.focus(buttonsZone);
      const again = keepIndex >= 0 ? buttonsZone.items()[keepIndex] : null;
      if (again) buttonsZone.focusElement(again);
    }
  }

  async function toggleFavorite() {
    favorite = await core.library.toggleFavorite(sourceId, 'MOVIE', id);
    render();
  }

  async function loadProgress() {
    progress = await core.library.progress(sourceId, 'MOVIE', id).catch(() => null);
    render();
  }

  (async () => {
    const movie = await core.catalog.movie(sourceId, id).catch(() => null);
    if (!movie) {
      clear(body);
      body.appendChild(emptyState('movie', 'Nessun film in questa categoria'));
      scope.zones = [topBar.zone];
      scope.ensure();
      return;
    }
    detail = { movie, plot: null, genre: null, cast: null, director: null, releaseDate: null, durationSec: null, backdrop: null, trailerYoutubeId: null };
    favorite = await core.library.isFavorite(sourceId, 'MOVIE', id).catch(() => false);
    progress = await core.library.progress(sourceId, 'MOVIE', id).catch(() => null);
    render();
    // Extra info comes from the network: keep the basic page if it fails.
    try { detail = await core.catalog.movieDetail(movie); render(); } catch { /* keep basic */ }
  })();

  // The player saves the position while it closes: re-read on every progress write (Android fix).
  let visible = true;
  const unsub = core.subscribe('library', () => { if (visible) loadProgress(); });
  return { el, scope, onShow: () => { visible = true; loadProgress(); }, onHide: () => { visible = false; }, destroy: unsub };
}

// Navigation actions shared by every screen (Android ui/nav/AppActions + PlayerLauncher).
import type { Channel, EpgProgram, Episode, Movie, Series, WatchProgress } from '../core/api.ts';
import { ALL_CATEGORY } from '../core/api.ts';
import { app } from './app.ts';
import { movieScreen } from './screens/movie.ts';
import { seriesScreen } from './screens/series.ts';
import { guideScreen } from './screens/guide.ts';
import { searchScreen } from './screens/search.ts';
import { settingsScreen } from './screens/settings.ts';
import { playerScreen, type PlayRequest } from './player/playerScreen.ts';

function play(req: PlayRequest): void {
  app().push(playerScreen(req));
}

export const actions = {
  openMovie(sourceId: number, id: string): void { app().push(movieScreen(sourceId, id)); },
  openSeries(sourceId: number, id: string): void { app().push(seriesScreen(sourceId, id)); },
  openGuide(sourceId: number, categoryId: string): void { app().push(guideScreen(sourceId, categoryId)); },
  openSearch(): void { app().push(searchScreen()); },
  openSettings(): void { app().push(settingsScreen()); },

  async playChannel(channel: Channel, categoryId: string | null): Promise<void> {
    let list: Channel[] = [];
    try { list = await app().core.catalog.channels(channel.sourceId, categoryId || channel.categoryId || ALL_CATEGORY); } catch { /* single channel */ }
    if (!list.some((c) => c.id === channel.id)) list = [channel];
    play({ kind: 'live', channel, list });
  },
  playCatchup(channel: Channel, program: EpgProgram): void { play({ kind: 'catchup', channel, program }); },
  playMovie(movie: Movie, startMs = 0): void { play({ kind: 'movie', movie, startMs }); },
  playEpisode(series: Series, episode: Episode, episodes: Episode[], startMs = 0): void {
    play({ kind: 'episode', series, episode, episodes, startMs });
  },

  /** "Continua a guardare" / "Canali recenti". */
  async playProgress(p: WatchProgress): Promise<void> {
    const core = app().core;
    try {
      if (p.type === 'LIVE') {
        const ch = await core.catalog.channel(p.sourceId, p.itemId);
        if (ch) await actions.playChannel(ch, ch.categoryId);
        else app().toast('Canale non trovato');
      } else if (p.type === 'MOVIE') {
        const m = await core.catalog.movie(p.sourceId, p.itemId);
        if (m) actions.playMovie(m, p.durationMs > 0 && p.positionMs / p.durationMs < 0.95 ? p.positionMs : 0);
        else app().toast('Film non trovato');
      } else if (p.type === 'EPISODE' && p.parentId) {
        const s = await core.catalog.seriesById(p.sourceId, p.parentId);
        if (!s) { app().toast('Serie non trovata'); return; }
        const d = await core.catalog.seriesDetail(s);
        const ep = d.episodes.find((e) => e.id === p.itemId);
        if (ep) actions.playEpisode(d.series, ep, d.episodes, p.durationMs > 0 && p.positionMs / p.durationMs < 0.95 ? p.positionMs : 0);
        else app().toast('Episodio non trovato');
      }
    } catch {
      app().toast('Impossibile aprire il contenuto');
    }
  },
};

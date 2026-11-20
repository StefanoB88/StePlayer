// Navigation actions shared by every screen (TV app ui/actions.ts).
import React, { useMemo } from 'react';
import type { Channel, Episode, Movie, Series, WatchProgress } from '../../../tizenApp/src/core/index.ts';
import { useNav } from './nav';
import { useSession } from './session';
import { PlayerScreen, type PlayRequest } from './screens/Player';
import { MovieScreen, SeriesScreen } from './screens/Details';

const ALL = '__all__';

export function useActions() {
  const nav = useNav();
  const { core } = useSession();
  return useMemo(() => {
    const play = (req: PlayRequest) => nav.push({ render: () => <PlayerScreen req={req} /> });
    const a = {
      openMovie: (m: Movie) => nav.push({ render: () => <MovieScreen movie={m} /> }),
      openSeries: (s: Series) => nav.push({ render: () => <SeriesScreen series={s} /> }),
      async playChannel(channel: Channel, categoryId: string | null) {
        let list: Channel[] = [];
        try { list = await core.catalog.channels(channel.sourceId, categoryId || channel.categoryId || ALL); } catch { /* single channel */ }
        if (!list.some((c) => c.id === channel.id)) list = [channel];
        play({ kind: 'live', channel, list });
      },
      playMovie: (movie: Movie, startMs = 0) => play({ kind: 'movie', movie, startMs }),
      playEpisode: (series: Series, episode: Episode, episodes: Episode[], startMs = 0) => play({ kind: 'episode', series, episode, episodes, startMs }),
      /** "Continua a guardare" / "Canali recenti". */
      async playProgress(p: WatchProgress) {
        const resume = p.durationMs > 0 && p.positionMs / p.durationMs < 0.95 ? p.positionMs : 0;
        if (p.type === 'LIVE') {
          const ch = await core.catalog.channel(p.sourceId, p.itemId);
          if (ch) await a.playChannel(ch, ch.categoryId);
        } else if (p.type === 'MOVIE') {
          const m = await core.catalog.movie(p.sourceId, p.itemId);
          if (m) a.playMovie(m, resume);
        } else if (p.type === 'EPISODE' && p.parentId) {
          const s = await core.catalog.seriesById(p.sourceId, p.parentId);
          if (!s) return;
          const d = await core.catalog.seriesDetail(s);
          const ep = d.episodes.find((e) => e.id === p.itemId);
          if (ep) a.playEpisode(d.series, ep, d.episodes, resume);
        }
      },
    };
    return a;
  }, [nav, core]);
}

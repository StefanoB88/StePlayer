// Film and series pages: artwork, details, "Guarda ora" / "Riprendi da …", favourite; series: seasons and episodes.
import React, { useState } from 'react';
import { FlatList, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Episode, Movie, Series } from '../../../../tizenApp/src/core/index.ts';
import { Art, Button, Chip, Focusable, Loading, ProgressBar } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { C, F, S } from '../theme';
import { duration } from '../format';

export function MovieScreen({ movie }: { movie: Movie }) {
  const { core } = useSession();
  const actions = useActions();
  const detail = useCoreData(() => core.catalog.movieDetail(movie), [movie.id]);
  const progress = useCoreData(() => core.library.progress(movie.sourceId, 'MOVIE', movie.id), [movie.id], ['library']);
  const fav = useCoreData(() => core.library.isFavorite(movie.sourceId, 'MOVIE', movie.id), [movie.id], ['library']);
  const resume = progress && progress.positionMs > 30_000 && !(progress.durationMs > 0 && progress.positionMs / progress.durationMs >= 0.95) ? progress.positionMs : 0;
  const meta = [movie.year, detail?.genre, detail?.durationSec ? duration(detail.durationSec * 1000) : null, movie.rating ? `★ ${movie.rating.toFixed(1)}` : null].filter(Boolean).join(' · ');
  return (
    <ScrollView style={styles.screen} contentContainerStyle={styles.content}>
      <View style={styles.row}>
        <Art uri={movie.poster} title={movie.name} style={styles.poster} />
        <View style={styles.info}>
          <Text style={F.display} numberOfLines={2}>{movie.name}</Text>
          {meta ? <Text style={[F.body2, { marginTop: 4 }]}>{meta}</Text> : null}
          <View style={[styles.row, { marginTop: 14 }]}>
            {resume ? <Button label={`Riprendi da ${duration(resume)}`} primary preferred onPress={() => actions.playMovie(movie, resume)} style={{ marginRight: 10 }} /> : null}
            <Button label={resume ? 'Dall\'inizio' : 'Guarda ora'} primary={!resume} preferred={!resume} onPress={() => actions.playMovie(movie, 0)} style={{ marginRight: 10 }} />
            <Button label={fav ? '♥ Nei preferiti' : '♡ Preferiti'} onPress={() => core.library.toggleFavorite(movie.sourceId, 'MOVIE', movie.id)} />
          </View>
          {resume && progress ? <ProgressBar value={progress.positionMs / Math.max(1, progress.durationMs)} style={{ marginTop: 10, width: 260 }} /> : null}
          {detail ? (
            <>
              {detail.plot ? <Text style={[F.body, { marginTop: 14 }]} numberOfLines={6}>{detail.plot}</Text> : null}
              {detail.cast ? <Text style={[F.body2, { marginTop: 8 }]} numberOfLines={2}>Cast  {detail.cast}</Text> : null}
              {detail.director ? <Text style={F.body2} numberOfLines={1}>Regia  {detail.director}</Text> : null}
            </>
          ) : null}
        </View>
      </View>
    </ScrollView>
  );
}

export function SeriesScreen({ series }: { series: Series }) {
  const { core } = useSession();
  const actions = useActions();
  const detail = useCoreData(() => core.catalog.seriesDetail(series), [series.id]);
  const progress = useCoreData(() => core.library.seriesProgress(series.sourceId, series.id), [series.id], ['library']);
  const fav = useCoreData(() => core.library.isFavorite(series.sourceId, 'SERIES', series.id), [series.id], ['library']);
  const [season, setSeason] = useState<number | null>(null);
  if (!detail) return <View style={styles.screen}><Loading /></View>;
  const seasons = detail.seasons.length ? detail.seasons : [1];
  const active = season ?? seasons[0];
  const episodes = detail.episodes.filter((e) => e.season === active);
  // "Riprendi": the episode watched most recently and not finished, else the first one.
  let next: Episode | null = detail.episodes[0] || null;
  let nextPos = 0;
  if (progress && progress.size) {
    const last = Array.from(progress.values()).sort((a, b) => b.updatedAt - a.updatedAt)[0];
    const ep = detail.episodes.find((e) => e.id === last.itemId);
    if (ep) {
      const done = last.durationMs > 0 && last.positionMs / last.durationMs >= 0.95;
      next = done ? detail.episodes[detail.episodes.indexOf(ep) + 1] || ep : ep;
      nextPos = done ? 0 : last.positionMs;
    }
  }
  return (
    <View style={styles.screen}>
      <View style={[styles.row, styles.content]}>
        <Art uri={series.cover} title={series.name} style={styles.posterSmall} />
        <View style={styles.info}>
          <Text style={F.display} numberOfLines={1}>{series.name}</Text>
          <Text style={[F.body2, { marginTop: 4 }]}>{[series.year, series.genre, series.rating ? `★ ${series.rating.toFixed(1)}` : null].filter(Boolean).join(' · ')}</Text>
          <View style={[styles.row, { marginTop: 12 }]}>
            {next ? (
              <Button label={`${nextPos > 30_000 ? `Riprendi da ${duration(nextPos)}` : 'Riproduci'} · S${next.season} E${next.number}`} primary preferred
                onPress={() => actions.playEpisode(detail.series, next!, detail.episodes, nextPos > 30_000 ? nextPos : 0)} style={{ marginRight: 10 }} />
            ) : null}
            <Button label={fav ? '♥ Nei preferiti' : '♡ Preferiti'} onPress={() => core.library.toggleFavorite(series.sourceId, 'SERIES', series.id)} />
          </View>
          {series.plot ? <Text style={[F.body, { marginTop: 10 }]} numberOfLines={3}>{series.plot}</Text> : null}
        </View>
      </View>
      <View style={[styles.row, { paddingHorizontal: S.margin, marginBottom: 8 }]}>
        {seasons.map((s) => <Chip key={s} label={`Stagione ${s}`} selected={s === active} onPress={() => setSeason(s)} onFocus={() => setSeason(s)} />)}
      </View>
      <FlatList
        data={episodes}
        keyExtractor={(e) => e.id}
        contentContainerStyle={{ paddingHorizontal: S.margin, paddingBottom: 24 }}
        renderItem={({ item }) => {
          const p = progress?.get(item.id);
          return (
            <Focusable onPress={() => actions.playEpisode(detail.series, item, detail.episodes, p && p.positionMs > 30_000 && !(p.durationMs > 0 && p.positionMs / p.durationMs >= 0.95) ? p.positionMs : 0)} style={styles.episode}>
              {() => (
                <View style={styles.row}>
                  <Art uri={item.image || series.cover} title={series.name} style={styles.epArt} />
                  <View style={{ flex: 1, marginLeft: 12 }}>
                    <Text style={F.title} numberOfLines={1}>{item.number}. {item.title}</Text>
                    {item.durationSec ? <Text style={F.body2}>{Math.round(item.durationSec / 60)} min</Text> : null}
                    {p && p.durationMs > 0 ? <ProgressBar value={p.positionMs / p.durationMs} style={{ marginTop: 6, width: 200 }} /> : null}
                  </View>
                </View>
              )}
            </Focusable>
          );
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg },
  content: { padding: S.margin },
  row: { flexDirection: 'row', alignItems: 'center' },
  poster: { width: 180, height: 270 },
  posterSmall: { width: 110, height: 165 },
  info: { flex: 1, marginLeft: 22, alignSelf: 'flex-start' },
  episode: { backgroundColor: C.surface, borderRadius: S.radius, padding: 8, marginBottom: 8, borderWidth: 2, borderColor: 'transparent' },
  epArt: { width: 120, height: 68 },
});

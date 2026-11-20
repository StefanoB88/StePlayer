// Home: "Continua a guardare", "Canali recenti", "Canali preferiti", "Film aggiunti di recente".
import React from 'react';
import { FlatList, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Channel, Movie, WatchProgress } from '../../../../tizenApp/src/core/index.ts';
import { Art, Empty, Focusable, Poster, ProgressBar } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { C, F, S } from '../theme';

export function HomeTab() {
  const { core, source } = useSession();
  const actions = useActions();
  const id = source ? source.id : -1;
  const cont = useCoreData(() => core.library.continueWatching(id, 20), [id], ['library', 'catalog']);
  const recent = useCoreData(() => core.library.recentChannels(id, 20), [id], ['library', 'catalog']);
  const favs = useCoreData(() => core.catalog.channels(id, '__fav__'), [id], ['library', 'catalog']);
  const movies = useCoreData(() => core.catalog.recentMovies(id, 20), [id], ['catalog']);
  const empty = cont && recent && favs && movies && !cont.length && !recent.length && !favs.length && !movies.length;
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={[F.headline, { marginBottom: 6 }]}>{source ? source.name : 'StePlayer'}</Text>
      {empty ? <Empty title="Ancora niente da mostrare" message="Apri Live, Film o Serie: qui troverai ciò che guardi e i tuoi preferiti." /> : null}
      <Row title="Continua a guardare" data={cont} render={(p: WatchProgress) => (
        <Focusable key={p.itemId} onPress={() => actions.playProgress(p)} style={styles.wide}>
          {() => (
            <>
              <Art uri={p.image} title={p.title} style={styles.wideArt} />
              <Text style={styles.cardTitle} numberOfLines={1}>{p.title}</Text>
              {p.subtitle ? <Text style={styles.cardSub} numberOfLines={1}>{p.subtitle}</Text> : null}
              {p.durationMs > 0 ? <ProgressBar value={p.positionMs / p.durationMs} style={{ marginTop: 4 }} /> : null}
            </>
          )}
        </Focusable>
      )} />
      <Row title="Canali recenti" data={recent} render={(p: WatchProgress) => (
        <ChannelTile key={p.itemId} name={p.title} logo={p.image} onPress={() => actions.playProgress(p)} />
      )} />
      <Row title="Canali preferiti" data={favs} render={(c: Channel) => (
        <ChannelTile key={c.id} name={c.name} logo={c.logo} onPress={() => actions.playChannel(c, '__fav__')} />
      )} />
      <Row title="Film aggiunti di recente" data={movies} render={(m: Movie) => (
        <Poster key={m.id} title={m.name} subtitle={m.year ? String(m.year) : null} uri={m.poster} onPress={() => actions.openMovie(m)} />
      )} />
    </ScrollView>
  );
}

function Row<T>({ title, data, render }: { title: string; data: T[] | undefined; render: (item: T, index: number) => React.ReactElement }) {
  if (!data || !data.length) return null;
  return (
    <View style={{ marginTop: 14 }}>
      <Text style={[F.title, { marginBottom: 8 }]}>{title}</Text>
      <FlatList horizontal data={data} keyExtractor={(_, i) => String(i)} renderItem={({ item, index }) => render(item, index)} showsHorizontalScrollIndicator={false} />
    </View>
  );
}

function ChannelTile({ name, logo, onPress }: { name: string; logo: string | null; onPress: () => void }) {
  return (
    <Focusable onPress={onPress} style={styles.tile}>
      {() => (
        <>
          <Art uri={logo} title={name} style={styles.tileArt} contain />
          <Text style={styles.cardTitle} numberOfLines={1}>{name}</Text>
        </>
      )}
    </Focusable>
  );
}

const styles = StyleSheet.create({
  content: { padding: S.margin, paddingBottom: 40 },
  wide: { width: 200, marginRight: 12, borderRadius: 10, padding: 2, borderWidth: 2, borderColor: 'transparent' },
  wideArt: { width: 192, height: 108 },
  tile: { width: 120, marginRight: 12, borderRadius: 10, padding: 2, borderWidth: 2, borderColor: 'transparent' },
  tileArt: { width: 112, height: 70, backgroundColor: C.elevated },
  cardTitle: { color: C.text, fontSize: 12, fontWeight: '600', marginTop: 6 },
  cardSub: { color: C.text2, fontSize: 11 },
});

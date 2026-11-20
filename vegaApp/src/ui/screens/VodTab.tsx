// Film / Serie: category chips (Tutti, Preferiti + the list's own) and a poster grid loaded page by page.
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { FlatList, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Movie, Series } from '../../../../tizenApp/src/core/index.ts';
import { Chip, Empty, Loading, Poster } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { F, S } from '../theme';

const PAGE = 60;
const COLUMNS = 6;
type Item = Movie | Series;

export function VodTab({ type }: { type: 'MOVIE' | 'SERIES' }) {
  const { core, source } = useSession();
  const actions = useActions();
  const id = source ? source.id : -1;
  const cats = useCoreData(() => core.catalog.categories(id, type), [id, type], ['catalog']);
  const [cat, setCat] = useState('__all__');
  const [items, setItems] = useState<Item[] | null>(null);
  const [done, setDone] = useState(false);

  const load = useCallback(async (offset: number): Promise<Item[]> => (
    type === 'MOVIE' ? core.catalog.movies(id, cat, PAGE, offset) : core.catalog.series(id, cat, PAGE, offset)
  ), [core, id, cat, type]);

  useEffect(() => {
    let alive = true;
    setItems(null);
    setDone(false);
    load(0).then((page) => { if (alive) { setItems(page); setDone(page.length < PAGE); } }).catch(() => { if (alive) setItems([]); });
    const off = core.subscribe('catalog', () => { load(0).then((p) => { if (alive) { setItems(p); setDone(p.length < PAGE); } }).catch(() => undefined); });
    return () => { alive = false; off(); };
  }, [core, load]);

  const more = () => {
    if (!items || done) return;
    load(items.length).then((page) => { setItems((cur) => (cur ? [...cur, ...page] : page)); if (page.length < PAGE) setDone(true); }).catch(() => setDone(true));
  };

  const chips = useMemo(() => [{ id: '__all__', name: 'Tutti' }, { id: '__fav__', name: 'Preferiti' }, ...(cats || []).map((c) => ({ id: c.id, name: c.name }))], [cats]);
  return (
    <View style={styles.root}>
      <Text style={[F.headline, styles.title]}>{type === 'MOVIE' ? 'Film' : 'Serie'}</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chips} contentContainerStyle={{ paddingHorizontal: S.margin }}>
        {chips.map((c, i) => <Chip key={c.id} label={c.name} selected={cat === c.id} onPress={() => setCat(c.id)} preferred={i === 0} />)}
      </ScrollView>
      {!items ? <Loading /> : !items.length ? <Empty title={cat === '__fav__' ? 'Nessun preferito' : 'Niente in questa categoria'} /> : (
        <FlatList
          data={items}
          numColumns={COLUMNS}
          keyExtractor={(it) => it.id}
          contentContainerStyle={{ paddingHorizontal: S.margin, paddingBottom: 30 }}
          onEndReached={more}
          onEndReachedThreshold={0.6}
          initialNumToRender={18}
          renderItem={({ item }) => type === 'MOVIE'
            ? <Poster title={item.name} subtitle={(item as Movie).year ? String((item as Movie).year) : null} uri={(item as Movie).poster} onPress={() => actions.openMovie(item as Movie)} />
            : <Poster title={item.name} subtitle={(item as Series).year ? String((item as Series).year) : null} uri={(item as Series).cover} onPress={() => actions.openSeries(item as Series)} />}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, paddingTop: S.margin },
  title: { paddingHorizontal: S.margin },
  chips: { flexGrow: 0, marginVertical: 10 },
});

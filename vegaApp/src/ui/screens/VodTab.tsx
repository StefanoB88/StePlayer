// Film / Serie: category chips (Tutti, Preferiti + the list's own) and a poster grid loaded page by page.
// The search bar searches only this section: its results replace the grid.
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import type { Movie, Series } from '../../../../tizenApp/src/core/index.ts';
import { ChipStrip, Empty, Field, Loading, Poster } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { F, S } from '../theme';
import { useSectionSearch } from '../sectionSearch';

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
  const search = useSectionSearch();
  const res = search.results;
  const found: Item[] | null = res ? (type === 'MOVIE' ? res.movies.map((x) => x.movie) : res.series.map((x) => x.series)) : null;
  const shown = search.query ? found : items;

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
    if (search.query || !items || done) return;
    load(items.length).then((page) => { setItems((cur) => (cur ? [...cur, ...page] : page)); if (page.length < PAGE) setDone(true); }).catch(() => setDone(true));
  };

  const chips = useMemo(() => [{ id: '__all__', name: 'Tutti' }, { id: '__fav__', name: 'Preferiti' }, ...(cats || []).map((c) => ({ id: c.id, name: c.name }))], [cats]);
  return (
    <View style={styles.root}>
      <View style={styles.header}>
        <Text style={[F.headline, styles.title]} numberOfLines={1}>{type === 'MOVIE' ? 'Film' : 'Serie'}</Text>
        <Field value={search.text} onChange={search.setText} placeholder={type === 'MOVIE' ? 'Cerca film' : 'Cerca serie'} style={styles.search} />
      </View>
      {/* OK on a category ends the search and shows that category. */}
      <ChipStrip style={styles.chips} items={chips.map((c, i) => ({ key: c.id, label: c.name, selected: cat === c.id, onPress: () => { search.setText(''); setCat(c.id); }, preferred: i === 0 }))} />
      {!shown ? <Loading /> : !shown.length ? (
        search.query
          ? <Empty title={`Nessun risultato per «${search.query}»`} message="Prova con un'altra parola o controlla l'ortografia." />
          : <Empty title={cat === '__fav__' ? 'Nessun preferito' : 'Niente in questa categoria'} />
      ) : (
        <FlatList
          key={search.query ? 'search' : 'grid'}
          data={shown}
          numColumns={COLUMNS}
          keyExtractor={(it) => it.id}
          contentContainerStyle={{ paddingHorizontal: S.margin, paddingTop: 8, paddingBottom: 30 }}
          onEndReached={more}
          onEndReachedThreshold={0.6}
          initialNumToRender={18}
          renderItem={({ item, index }) => type === 'MOVIE'
            ? <Poster title={item.name} subtitle={(item as Movie).year ? String((item as Movie).year) : null} uri={(item as Movie).poster} preferred={search.refocus && index === 0} onPress={() => { search.remember(); actions.openMovie(item as Movie); }} />
            : <Poster title={item.name} subtitle={(item as Series).year ? String((item as Series).year) : null} uri={(item as Series).cover} preferred={search.refocus && index === 0} onPress={() => { search.remember(); actions.openSeries(item as Series); }} />}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, paddingTop: S.margin },
  header: { flexDirection: 'row', alignItems: 'center', paddingHorizontal: S.margin },
  title: { flex: 1, marginRight: 16 },
  search: { width: 320, marginBottom: 0 },
  chips: { flexGrow: 0, marginTop: 6, marginBottom: 2 },
});

// Live: categories (Tutti, Preferiti, Recenti + the list's own), channels with the programme on now and next.
// OK plays; long OK or the red key adds/removes a favourite. The search bar searches only channels (and programmes
// on now, which open their channel): its results replace the channel list.
import React, { useEffect, useMemo, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import { useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { Channel, ChannelWithEpg } from '../../../../tizenApp/src/core/index.ts';
import { Art, Empty, Field, Focusable, Loading, ProgressBar } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { C, F, S } from '../theme';
import { clock } from '../format';
import { useSectionSearch } from '../sectionSearch';

const VIRTUAL = [{ id: '__all__', name: 'Tutti' }, { id: '__fav__', name: 'Preferiti' }, { id: '__recent__', name: 'Recenti' }];

export function LiveTab() {
  const { core, source } = useSession();
  const actions = useActions();
  const id = source ? source.id : -1;
  const cats = useCoreData(() => core.catalog.categories(id, 'LIVE'), [id], ['catalog']);
  const [cat, setCat] = useState('__all__');
  const channels = useCoreData(() => core.catalog.channels(id, cat), [id, cat], ['catalog', 'library']);
  const [epg, setEpg] = useState<Map<string, ChannelWithEpg>>(new Map());
  const [focused, setFocused] = useState<Channel | null>(null);
  const search = useSectionSearch();
  const res = search.results;

  // Search results: the matching channels, then the channels airing a matching programme now.
  const found = useMemo(() => {
    if (!res) return null;
    const list = res.channels.map((x) => x.channel);
    const seen = new Set(list.map((c) => c.id));
    const live = new Map<string, ChannelWithEpg>();
    for (const p of res.liveNow) {
      if (seen.has(p.channel.id)) continue;
      seen.add(p.channel.id);
      list.push(p.channel);
      live.set(p.channel.id, { channel: p.channel, now: p.program, next: null });
    }
    return { list, live };
  }, [res]);
  const shown = search.query ? (found ? found.list : null) : channels;

  // Now/next for the channels shown (in slices, so big lists don't block the UI).
  useEffect(() => {
    if (!shown) return;
    let alive = true;
    const map = new Map<string, ChannelWithEpg>(found && shown === found.list ? found.live : []);
    if (map.size) setEpg(new Map(map));
    (async () => {
      for (let i = 0; i < shown.length && alive; i += 200) {
        const part = await core.epg.nowNext(id, shown.slice(i, i + 200)).catch(() => []);
        part.forEach((x) => map.set(x.channel.id, x));
        if (alive) setEpg(new Map(map));
      }
    })();
    return () => { alive = false; };
  }, [core, id, shown, found]);

  useTVEventHandler((ev) => {
    if (ev.eventType === 'red' && ev.eventKeyAction !== 1 && focused) core.library.toggleFavorite(focused.sourceId, 'LIVE', focused.id);
  });

  const allCats = useMemo(() => [...VIRTUAL, ...(cats || []).map((c) => ({ id: c.id, name: c.name }))], [cats]);
  return (
    <View style={styles.root}>
      <View style={styles.cats}>
        <Text style={[F.headline, { marginBottom: 10 }]}>Live</Text>
        <FlatList data={allCats} keyExtractor={(c) => c.id} renderItem={({ item }) => (
          // OK on a category ends the search and shows that category.
          <Focusable testID={`cat:${item.name}`} onPress={() => { search.setText(''); setCat(item.id); }} onFocus={() => setCat(item.id)} style={[styles.cat, cat === item.id && styles.catOn]}>
            {() => <Text style={[F.body, cat === item.id && { color: C.gold, fontWeight: '700' }]} numberOfLines={1}>{item.name}</Text>}
          </Focusable>
        )} />
      </View>
      <View style={styles.list}>
        <Field value={search.text} onChange={search.setText} placeholder="Cerca canali e programmi in onda" style={styles.search} />
        {!shown ? <Loading /> : !shown.length ? (
          search.query
            ? <Empty title={`Nessun risultato per «${search.query}»`} message="Prova con un'altra parola o controlla l'ortografia." />
            : <Empty title={cat === '__fav__' ? 'Nessun preferito' : 'Nessun canale'} message={cat === '__fav__' ? 'Tieni premuto OK su un canale (o premi il tasto rosso) per aggiungerlo ai preferiti.' : undefined} />
        ) : (
          <FlatList
            data={shown}
            keyExtractor={(c) => c.id}
            initialNumToRender={12}
            windowSize={7}
            renderItem={({ item, index }) => {
              const e = epg.get(item.id);
              const p = e && e.now ? (Date.now() - e.now.startAt) / Math.max(1, e.now.endAt - e.now.startAt) : 0;
              const hit = !!found && search.query !== '' && found.live.has(item.id);
              // A search result zaps within its own category, like the global search.
              const play = () => { if (search.query) { search.remember(); actions.playChannel(item, item.categoryId); } else actions.playChannel(item, cat); };
              return (
                <Focusable onPress={play} onLongPress={() => core.library.toggleFavorite(item.sourceId, 'LIVE', item.id)}
                  onFocus={() => setFocused(item)} preferred={search.refocus && index === 0} testID={`ch:${item.name}`} style={styles.channel} wide>
                  {() => (
                    <View style={styles.row}>
                      <Art uri={item.logo} title={item.name} style={styles.logo} contain />
                      <View style={{ flex: 1, marginLeft: 12 }}>
                        <Text style={F.title} numberOfLines={1}>{item.num != null ? `${item.num}  ` : ''}{item.name}</Text>
                        {e && e.now ? (
                          <>
                            <Text style={[F.body2, hit && { color: C.gold }]} numberOfLines={1}>{hit ? `In onda ora: ${e.now.title}` : e.now.title}</Text>
                            <ProgressBar value={p} style={{ marginTop: 4, width: '70%' }} />
                            {e.next ? <Text style={[F.label, { marginTop: 3 }]} numberOfLines={1}>{clock(e.next.startAt)}  {e.next.title}</Text> : null}
                          </>
                        ) : null}
                      </View>
                    </View>
                  )}
                </Focusable>
              );
            }}
          />
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, flexDirection: 'row' },
  cats: { width: 200, padding: S.margin, paddingRight: 8 },
  cat: { paddingVertical: 8, paddingHorizontal: 12, borderRadius: 10, marginBottom: 2, borderWidth: 2, borderColor: 'transparent' },
  catOn: { backgroundColor: 'rgba(255,162,31,0.15)' },
  list: { flex: 1, paddingVertical: S.margin, paddingRight: S.margin },
  search: { marginBottom: 8 },
  channel: { backgroundColor: C.surface, borderRadius: S.radius, padding: 8, marginBottom: 6, borderWidth: 2, borderColor: 'transparent' },
  row: { flexDirection: 'row', alignItems: 'center' },
  logo: { width: 72, height: 46 },
});

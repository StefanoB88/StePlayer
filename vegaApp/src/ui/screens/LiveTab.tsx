// Live: categories (Tutti, Preferiti, Recenti + the list's own), channels with the programme on now and next.
// OK plays; long OK or the red key adds/removes a favourite.
import React, { useEffect, useMemo, useState } from 'react';
import { FlatList, StyleSheet, Text, View } from 'react-native';
import { useTVEventHandler } from '@amazon-devices/react-native-kepler';
import type { Channel, ChannelWithEpg } from '../../../../tizenApp/src/core/index.ts';
import { Art, Empty, Focusable, Loading, ProgressBar } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { C, F, S } from '../theme';
import { clock } from '../format';

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

  // Now/next for the category (in slices, so big lists don't block the UI).
  useEffect(() => {
    if (!channels) return;
    let alive = true;
    const map = new Map<string, ChannelWithEpg>();
    (async () => {
      for (let i = 0; i < channels.length && alive; i += 200) {
        const part = await core.epg.nowNext(id, channels.slice(i, i + 200)).catch(() => []);
        part.forEach((x) => map.set(x.channel.id, x));
        if (alive) setEpg(new Map(map));
      }
    })();
    return () => { alive = false; };
  }, [core, id, channels]);

  useTVEventHandler((ev) => {
    if (ev.eventType === 'red' && ev.eventKeyAction !== 1 && focused) core.library.toggleFavorite(focused.sourceId, 'LIVE', focused.id);
  });

  const allCats = useMemo(() => [...VIRTUAL, ...(cats || []).map((c) => ({ id: c.id, name: c.name }))], [cats]);
  return (
    <View style={styles.root}>
      <View style={styles.cats}>
        <Text style={[F.headline, { marginBottom: 10 }]}>Live</Text>
        <FlatList data={allCats} keyExtractor={(c) => c.id} renderItem={({ item }) => (
          <Focusable testID={`cat:${item.name}`} onPress={() => setCat(item.id)} onFocus={() => setCat(item.id)} style={[styles.cat, cat === item.id && styles.catOn]}>
            {() => <Text style={[F.body, cat === item.id && { color: C.gold, fontWeight: '700' }]} numberOfLines={1}>{item.name}</Text>}
          </Focusable>
        )} />
      </View>
      <View style={styles.list}>
        {!channels ? <Loading /> : !channels.length ? (
          <Empty title={cat === '__fav__' ? 'Nessun preferito' : 'Nessun canale'} message={cat === '__fav__' ? 'Tieni premuto OK su un canale (o premi il tasto rosso) per aggiungerlo ai preferiti.' : undefined} />
        ) : (
          <FlatList
            data={channels}
            keyExtractor={(c) => c.id}
            initialNumToRender={12}
            windowSize={7}
            renderItem={({ item }) => {
              const e = epg.get(item.id);
              const p = e && e.now ? (Date.now() - e.now.startAt) / Math.max(1, e.now.endAt - e.now.startAt) : 0;
              return (
                <Focusable onPress={() => actions.playChannel(item, cat)} onLongPress={() => core.library.toggleFavorite(item.sourceId, 'LIVE', item.id)}
                  onFocus={() => setFocused(item)} testID={`ch:${item.name}`} style={styles.channel}>
                  {() => (
                    <View style={styles.row}>
                      <Art uri={item.logo} title={item.name} style={styles.logo} contain />
                      <View style={{ flex: 1, marginLeft: 12 }}>
                        <Text style={F.title} numberOfLines={1}>{item.num != null ? `${item.num}  ` : ''}{item.name}</Text>
                        {e && e.now ? (
                          <>
                            <Text style={F.body2} numberOfLines={1}>{e.now.title}</Text>
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
  channel: { backgroundColor: C.surface, borderRadius: S.radius, padding: 8, marginBottom: 6, borderWidth: 2, borderColor: 'transparent' },
  row: { flexDirection: 'row', alignItems: 'center' },
  logo: { width: 72, height: 46 },
});

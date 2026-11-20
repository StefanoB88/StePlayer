// Smart search over channels, programmes, films and series (shared core: typo-tolerant, Italian synonyms).
import React, { useEffect, useState } from 'react';
import { FlatList, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { SmartSearchResults } from '../../../../tizenApp/src/core/index.ts';
import { Chip, Empty, Field, Focusable, Poster } from '../components';
import { useActions } from '../actions';
import { useCoreData, useSession } from '../session';
import { C, F, S } from '../theme';

export function SearchTab() {
  const { core, source } = useSession();
  const actions = useActions();
  const id = source ? source.id : -1;
  const [q, setQ] = useState('');
  const [res, setRes] = useState<SmartSearchResults | null>(null);
  const recent = useCoreData(() => core.search.recentQueries(), [], ['settings']);

  useEffect(() => {
    if (q.trim().length < 2) { setRes(null); return; }
    let alive = true;
    const t = setTimeout(() => { core.search.search(id, q).then((r) => { if (alive) setRes(r); }).catch(() => undefined); }, 350);
    return () => { alive = false; clearTimeout(t); };
  }, [core, id, q]);

  const remember = () => { if (q.trim().length >= 2) core.search.rememberQuery(q.trim()).catch(() => undefined); };
  const nothing = res && !res.channels.length && !res.movies.length && !res.series.length && !res.liveNow.length;
  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={[F.headline, { marginBottom: 10 }]}>Cerca</Text>
      <Field label="Canali, programmi, film, serie" value={q} onChange={setQ} preferred />
      {!res && recent && recent.length ? (
        <View style={styles.row}>{recent.slice(0, 8).map((r) => <Chip key={r} label={r} selected={false} onPress={() => setQ(r)} />)}</View>
      ) : null}
      {res && res.correctedQuery ? <Text style={F.body2}>Risultati per «{res.correctedQuery}»</Text> : null}
      {nothing ? <Empty title={`Nessun risultato per «${q}»`} message="Prova con un'altra parola o controlla l'ortografia." /> : null}
      {res && res.liveNow.length ? <Section title="In onda ora">{res.liveNow.slice(0, 12).map((h) => (
        <Focusable key={`${h.channel.id}-${h.program.startAt}`} onPress={() => { remember(); actions.playChannel(h.channel, null); }} style={styles.chipCard}>
          {() => <><Text style={F.title} numberOfLines={1}>{h.program.title}</Text><Text style={F.body2} numberOfLines={1}>{h.channel.name}</Text></>}
        </Focusable>
      ))}</Section> : null}
      {res && res.channels.length ? <Section title="Canali">{res.channels.slice(0, 20).map((h) => (
        <Focusable key={h.channel.id} onPress={() => { remember(); actions.playChannel(h.channel, null); }} style={styles.chipCard}>
          {() => <Text style={F.title} numberOfLines={1}>{h.channel.name}</Text>}
        </Focusable>
      ))}</Section> : null}
      {res && res.movies.length ? <Section title="Film">{res.movies.slice(0, 20).map((h) => (
        <Poster key={h.movie.id} title={h.movie.name} subtitle={h.movie.year ? String(h.movie.year) : null} uri={h.movie.poster} onPress={() => { remember(); actions.openMovie(h.movie); }} />
      ))}</Section> : null}
      {res && res.series.length ? <Section title="Serie">{res.series.slice(0, 20).map((h) => (
        <Poster key={h.series.id} title={h.series.name} uri={h.series.cover} onPress={() => { remember(); actions.openSeries(h.series); }} />
      ))}</Section> : null}
    </ScrollView>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={{ marginTop: 14 }}>
      <Text style={[F.title, { marginBottom: 8 }]}>{title}</Text>
      <FlatList horizontal data={React.Children.toArray(children)} keyExtractor={(_, i) => String(i)} renderItem={({ item }) => item as React.ReactElement} showsHorizontalScrollIndicator={false} />
    </View>
  );
}

const styles = StyleSheet.create({
  content: { padding: S.margin, paddingBottom: 40 },
  row: { flexDirection: 'row', flexWrap: 'wrap', marginTop: 6 },
  chipCard: { width: 180, backgroundColor: C.surface, borderRadius: 10, padding: 10, marginRight: 10, borderWidth: 2, borderColor: 'transparent' },
});

// Settings: lists (use, update now, delete, add), legal notes and version.
import React, { useState } from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button } from '../components';
import { useNav } from '../nav';
import { useSession } from '../session';
import { C, F, S } from '../theme';
import { AddSourceScreen, SyncScreen } from './Onboarding';

export function SettingsTab() {
  const { core, source, sources, reload } = useSession();
  const nav = useNav();
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);

  const sync = (id: number) => nav.push({ render: () => <SyncScreen sourceId={id} onDone={() => nav.pop()} onFailedBack={() => nav.pop()} /> });
  const add = () => nav.push({
    render: () => <AddSourceScreen onCancel={() => nav.pop()} onSaved={(id) => { nav.pop(); reload(); sync(id); }} />,
  });

  return (
    <ScrollView contentContainerStyle={styles.content}>
      <Text style={[F.headline, { marginBottom: 12 }]}>Impostazioni</Text>
      <Text style={[F.title, { marginBottom: 8 }]}>Liste</Text>
      {sources.map((s) => (
        <View key={s.id} style={styles.card}>
          <View style={{ flex: 1 }}>
            <Text style={F.title}>{s.name}{source && source.id === s.id ? '  ·  in uso' : ''}</Text>
            <Text style={F.body2}>{s.type === 'M3U' ? 'Lista M3U' : 'Accesso al server'}{s.lastSyncAt ? ` · aggiornata ${new Date(s.lastSyncAt).toLocaleString('it-IT')}` : ''}</Text>
          </View>
          {source && source.id !== s.id ? <Button label="Usa" onPress={() => core.sources.setActive(s.id).then(reload)} style={styles.btn} /> : null}
          <Button label="Aggiorna" onPress={() => sync(s.id)} style={styles.btn} />
          {confirmDelete === s.id
            ? <Button label="Conferma eliminazione" primary onPress={() => { setConfirmDelete(null); core.sources.delete(s.id).then(reload); }} style={styles.btn} />
            : <Button label="Elimina" onPress={() => setConfirmDelete(s.id)} style={styles.btn} />}
        </View>
      ))}
      <Button label="Aggiungi lista" primary onPress={add} style={{ alignSelf: 'flex-start', marginTop: 6 }} />

      <Text style={[F.title, { marginTop: 22, marginBottom: 8 }]}>Note legali</Text>
      <Text style={F.body2}>StePlayer è esclusivamente un lettore multimediale neutrale: non fornisce, non vende e non include alcun contenuto audiovisivo, canale, lista o abbonamento. Tutti i dati restano su questo dispositivo.</Text>
      <Text style={[F.body2, { marginTop: 6 }]}>Informativa sulla privacy e termini d'uso: stefanob88.github.io/steplayerwebsite</Text>
      <Text style={[F.label, { marginTop: 16 }]}>StePlayer per Fire TV (Vega) · versione 1.0.0</Text>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: S.margin, paddingBottom: 40 },
  card: { flexDirection: 'row', alignItems: 'center', backgroundColor: C.surface, borderRadius: S.radius, padding: 12, marginBottom: 8 },
  btn: { marginLeft: 8, height: 34, paddingHorizontal: 14 },
});

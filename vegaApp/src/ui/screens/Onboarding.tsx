// First launch: legal notice (accept / refuse), "Aggiungi lista" (Lista M3U or Accesso al server) and the first sync
// with its progress. Same texts as the Samsung/LG/Android TV apps.
import React, { useEffect, useState } from 'react';
import { BackHandler, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Button, Chip, Field, Focusable, ProgressBar } from '../components';
import { C, F, S } from '../theme';
import { prefs } from '../prefs';
import { useSession } from '../session';
import { devLog } from '../../devlog';
import type { SyncState } from '../../../../tizenApp/src/core/index.ts';

const GATE = [
  '1. StePlayer è esclusivamente un lettore multimediale neutrale: non fornisce, non vende e non include alcun contenuto audiovisivo o abbonamento.',
  "2. Devi disporre dei diritti per riprodurre qualsiasi flusso o lista che inserisci nell'app.",
  "3. Lo sviluppatore declina ogni responsabilità per l'uso dell'app con contenuti protetti dal diritto d'autore senza autorizzazione.",
];

export function WelcomeScreen({ onAccepted }: { onAccepted: () => void }) {
  const [confirmed, setConfirmed] = useState(false);
  return (
    <View style={styles.screen}>
      <View style={styles.column}>
        <Text style={[F.display, styles.centerText]}>Benvenuto in StePlayer</Text>
        <Text style={[F.body2, styles.centerText, { marginTop: 4, marginBottom: 14 }]}>Lettore multimediale per le tue liste. Veloce, pulito e privato.</Text>
        <View style={styles.card}>{GATE.map((g) => <Text key={g} style={[F.body, { marginBottom: 6 }]}>{g}</Text>)}</View>
        <View style={styles.card}><Text style={F.body}>Tutti i dati restano su questo dispositivo. Nessun account, nessun tracciamento.</Text></View>
        <Focusable onPress={() => setConfirmed((c) => !c)} preferred style={styles.check}>
          {() => (
            <View style={styles.row}>
              <View style={[styles.box, confirmed && styles.boxOn]}>{confirmed ? <Text style={styles.tick}>✓</Text> : null}</View>
              <Text style={[F.body, { flex: 1 }]}>Confermo che userò StePlayer solo con contenuti che ho il diritto di guardare</Text>
            </View>
          )}
        </Focusable>
        <View style={[styles.row, { marginTop: 10 }]}>
          <Button label="Non accetto" onPress={() => BackHandler.exitApp()} style={{ flex: 1, marginRight: 12 }} />
          <Button label="Ho capito e accetto" primary disabled={!confirmed} style={{ flex: 2 }}
            onPress={() => { prefs.set('disclaimerAccepted', '1'); devLog('ui: terms accepted'); onAccepted(); }} />
        </View>
      </View>
    </View>
  );
}

export function AddSourceScreen({ onSaved, onCancel }: { onSaved: (id: number) => void; onCancel?: () => void }) {
  const { core } = useSession();
  const [type, setType] = useState<'m3u' | 'xtream'>('m3u');
  const [name, setName] = useState('');
  const [listUrl, setListUrl] = useState('');
  const [epgUrl, setEpgUrl] = useState('');
  const [server, setServer] = useState('');
  const [user, setUser] = useState('');
  const [pass, setPass] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const save = async () => {
    setBusy(true);
    setError(null);
    try {
      const id = type === 'm3u'
        ? await core.sources.add({ kind: 'm3u', name: name.trim() || 'Lista', listUrl: listUrl.trim(), epgUrl: epgUrl.trim() || null })
        : await core.sources.add({ kind: 'xtream', name: name.trim() || 'Server', serverUrl: server.trim(), username: user.trim(), password: pass });
      await core.sources.setActive(id);
      devLog(`ui: list saved (${type})`);
      onSaved(id);
    } catch (e) {
      setError((e as Error).message || 'Impossibile aggiungere la lista');
      devLog(`ui: add list error: ${(e as Error).message}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={styles.screen}>
      <ScrollView contentContainerStyle={styles.formColumn}>
        <Text style={F.headline}>Aggiungi lista</Text>
        <View style={[styles.row, { marginVertical: 12 }]}>
          <Chip label="Lista M3U" selected={type === 'm3u'} onPress={() => setType('m3u')} preferred />
          <Chip label="Accesso al server" selected={type === 'xtream'} onPress={() => setType('xtream')} />
        </View>
        <Text style={[F.body2, { marginBottom: 8 }]}>Suggerimento: puoi scrivere sulla TV anche dal telefono con l'app Amazon Fire TV.</Text>
        <Field label="Nome lista (qualsiasi cosa)" value={name} onChange={setName} />
        {type === 'm3u' ? (
          <>
            <Field label="URL lista" value={listUrl} onChange={setListUrl} placeholder="http://…/lista.m3u" />
            <Field label="URL EPG (opzionale)" value={epgUrl} onChange={setEpgUrl} />
          </>
        ) : (
          <>
            <Field label="Server (es. http://server.esempio:8080)" value={server} onChange={setServer} />
            <Field label="Username" value={user} onChange={setUser} />
            <Field label="Password" value={pass} onChange={setPass} secure />
          </>
        )}
        {error ? <Text style={styles.error}>{error}</Text> : null}
        <View style={[styles.row, { marginTop: 8 }]}>
          {onCancel ? <Button label="Annulla" onPress={onCancel} style={{ marginRight: 12 }} /> : null}
          <Button label={busy ? 'Verifica in corso…' : 'Salva e connetti'} primary disabled={busy || (type === 'm3u' ? !listUrl.trim() : !server.trim() || !user.trim())} onPress={save} />
        </View>
      </ScrollView>
    </View>
  );
}

const STEP: Record<string, string> = {
  AUTHENTICATING: 'Connessione al server…', LIVE: 'Canali live…', MOVIES: 'Film…', SERIES: 'Serie…', EPG: 'Guida TV…', SAVING: 'Salvataggio…',
};

export function SyncScreen({ sourceId, onDone, onFailedBack }: { sourceId: number; onDone: () => void; onFailedBack: () => void }) {
  const { core } = useSession();
  const [state, setState] = useState<SyncState>({ kind: 'running', step: 'AUTHENTICATING', progress: 0.02 });
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    const off = core.sync.onState((st) => { setState(st); if (st.kind !== 'running') devLog(`ui: sync ${st.kind}`); });
    core.sync.sync(sourceId).then(onDone, (e) => setState({ kind: 'failed', message: (e as Error).message }));
    return off;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);
  const failed = state.kind === 'failed';
  return (
    <View style={[styles.screen, styles.centered]}>
      <View style={styles.syncCard}>
        <Text style={[F.headline, styles.centerText]}>{failed ? 'Aggiornamento non riuscito' : 'Aggiornamento della lista'}</Text>
        {failed ? (
          <>
            <Text style={[F.body2, styles.centerText, { marginVertical: 14 }]}>{state.message}</Text>
            <View style={styles.row}>
              <Button label="Indietro" onPress={onFailedBack} style={{ flex: 1, marginRight: 12 }} />
              <Button label="Riprova" primary preferred onPress={() => setAttempt((a) => a + 1)} style={{ flex: 1 }} />
            </View>
          </>
        ) : (
          <>
            <Text style={[F.body2, styles.centerText, { marginVertical: 10 }]}>{state.kind === 'running' ? STEP[state.step] || '' : ''}</Text>
            <ProgressBar value={state.kind === 'running' ? state.progress : 1} />
            <Text style={[F.label, styles.centerText, { marginTop: 6 }]}>{Math.round((state.kind === 'running' ? state.progress : 1) * 100)}%</Text>
          </>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: C.bg },
  centered: { alignItems: 'center', justifyContent: 'center' },
  column: { flex: 1, width: 560, alignSelf: 'center', justifyContent: 'center', paddingVertical: S.margin },
  formColumn: { width: 580, alignSelf: 'center', paddingVertical: S.margin },
  centerText: { textAlign: 'center' },
  card: { backgroundColor: C.surface, borderRadius: S.radius, padding: 14, marginBottom: 10, borderWidth: 1, borderColor: C.elevated },
  check: { borderRadius: S.radius, padding: 10, borderWidth: 2, borderColor: 'transparent' },
  row: { flexDirection: 'row', alignItems: 'center' },
  box: { width: 22, height: 22, borderRadius: 6, borderWidth: 2, borderColor: C.text2, marginRight: 12, alignItems: 'center', justifyContent: 'center' },
  boxOn: { backgroundColor: C.accent, borderColor: C.accent },
  tick: { color: '#1A1205', fontWeight: '900', fontSize: 14 },
  error: { color: C.live, marginVertical: 6, fontSize: 13 },
  syncCard: { width: 420, backgroundColor: C.surface, borderRadius: 18, padding: 24 },
});

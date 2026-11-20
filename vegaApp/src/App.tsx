// StePlayer for Amazon Fire TV (Vega OS, React Native). Same lists, guide, search and sync logic as the Samsung/LG
// TV app (../tizenApp/src/core); playback through react-native-w3cmedia (HLS via Amazon's hls.js).
import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { getCore } from './coreHost';
import { devLog, DEV_AUTOLIST } from './devlog';
import { loadPrefs, prefs } from './ui/prefs';
import { NavHost, useNav } from './ui/nav';
import { SessionProvider, useSession } from './ui/session';
import { Loading } from './ui/components';
import { C, F } from './ui/theme';
import { AddSourceScreen, SyncScreen, WelcomeScreen } from './ui/screens/Onboarding';
import { ShellScreen } from './ui/screens/Shell';
import type { Core } from '../../tizenApp/src/core/index.ts';

export const App = () => {
  const [core, setCore] = useState<Core | null>(null);
  const [hasSource, setHasSource] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    (async () => {
      try {
        await loadPrefs();
        const c = await getCore();
        if (DEV_AUTOLIST && !(await c.sources.current())) {
          const id = await c.sources.add({ kind: 'm3u', name: 'Demo', listUrl: DEV_AUTOLIST, epgUrl: null });
          await c.sources.setActive(id);
          await c.sync.sync(id);
          prefs.set('disclaimerAccepted', '1');
        }
        setHasSource((await c.sources.current()) != null);
        setCore(c);
        devLog('app ready');
      } catch (e) {
        setError((e as Error).message || String(e));
        devLog(`boot error: ${(e as Error).stack || e}`);
      }
    })();
  }, []);
  if (error) {
    return (
      <View style={styles.center}>
        <Text style={F.headline}>Impossibile avviare StePlayer</Text>
        <Text style={[F.body2, { marginTop: 10 }]}>{error}</Text>
      </View>
    );
  }
  if (!core) return <View style={styles.root}><Loading /></View>;
  return (
    <SessionProvider core={core}>
      <View style={styles.root}>
        <NavHost initial={{ render: () => <Start hasSource={hasSource} /> }} />
      </View>
    </SessionProvider>
  );
};

/** First screen: terms → add list → sync → shell (or the shell straight away). */
function Start({ hasSource }: { hasSource: boolean }) {
  const nav = useNav();
  const { reload } = useSession();
  const [step, setStep] = useState<'welcome' | 'add' | 'shell'>(hasSource ? 'shell' : prefs.get('disclaimerAccepted') === '1' ? 'add' : 'welcome');
  if (step === 'welcome') return <WelcomeScreen onAccepted={() => setStep('add')} />;
  if (step === 'add') {
    return (
      <AddSourceScreen onSaved={(id) => {
        reload();
        nav.push({ render: () => <SyncScreen sourceId={id} onDone={() => { nav.reset({ render: () => <ShellScreen /> }); }} onFailedBack={() => nav.pop()} /> });
      }} />
    );
  }
  return <ShellScreen />;
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: C.bg },
  center: { flex: 1, backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center', padding: 40 },
});

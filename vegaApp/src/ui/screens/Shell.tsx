// Main screen: side rail (Cerca, Home, Live, Film, Serie, Impostazioni) and the selected section. Back on a section
// other than Home goes to Home; on Home it leaves the app (TV app behaviour).
import React, { useEffect, useState } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { TVFocusGuideView } from '@amazon-devices/react-native-kepler';
import { Focusable } from '../components';
import { useNav, useIsTop } from '../nav';
import { useSession } from '../session';
import { C, S } from '../theme';
import { HomeTab } from './HomeTab';
import { LiveTab } from './LiveTab';
import { VodTab } from './VodTab';
import { SearchTab } from './SearchTab';
import { SettingsTab } from './SettingsTab';

export type Tab = 'search' | 'home' | 'live' | 'movies' | 'series' | 'settings';
const ITEMS: Array<{ id: Tab; label: string; glyph: string }> = [
  { id: 'search', label: 'Cerca', glyph: '⌕' },
  { id: 'home', label: 'Home', glyph: '⌂' },
  { id: 'live', label: 'Live', glyph: '▶' },
  { id: 'movies', label: 'Film', glyph: '▤' },
  { id: 'series', label: 'Serie', glyph: '▦' },
  { id: 'settings', label: 'Impostazioni', glyph: '⚙' },
];

export function ShellScreen() {
  const nav = useNav();
  const top = useIsTop();
  const { core, source } = useSession();
  const [tab, setTab] = useState<Tab>('home');

  useEffect(() => {
    if (!top) return;
    nav.setBackHandler(() => { if (tab !== 'home') { setTab('home'); return true; } return false; });
  }, [nav, tab, top]);

  // Catalog older than its refresh interval: update quietly in the background (TV app behaviour).
  useEffect(() => { if (source) core.sync.syncIfStale(source.id).catch(() => undefined); }, [core, source]);

  return (
    <View style={styles.root}>
      <TVFocusGuideView style={styles.rail} autoFocus>
        <View style={styles.logo}><Text style={styles.logoGlyph}>▶</Text></View>
        {ITEMS.map((it) => (
          <Focusable key={it.id} testID={`rail:${it.id}`} onPress={() => setTab(it.id)} preferred={it.id === 'home'} style={[styles.railItem, tab === it.id && styles.railItemOn, it.id === 'settings' && { marginTop: 'auto' }]}>
            {(f) => (
              <>
                <Text style={[styles.railGlyph, (tab === it.id || f) && styles.railActive]}>{it.glyph}</Text>
                <Text style={[styles.railLabel, (tab === it.id || f) && styles.railActive]} numberOfLines={1}>{it.label}</Text>
              </>
            )}
          </Focusable>
        ))}
      </TVFocusGuideView>
      <View style={styles.content}>
        {tab === 'home' ? <HomeTab /> : null}
        {tab === 'live' ? <LiveTab /> : null}
        {tab === 'movies' ? <VodTab type="MOVIE" /> : null}
        {tab === 'series' ? <VodTab type="SERIES" /> : null}
        {tab === 'search' ? <SearchTab /> : null}
        {tab === 'settings' ? <SettingsTab /> : null}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, flexDirection: 'row', backgroundColor: C.bg },
  rail: { width: S.railWidth, backgroundColor: C.surface, paddingVertical: 16, alignItems: 'center' },
  logo: { width: 44, height: 44, borderRadius: 12, backgroundColor: C.elevated, alignItems: 'center', justifyContent: 'center', marginBottom: 14 },
  logoGlyph: { color: C.accent, fontSize: 22 },
  railItem: { width: 76, paddingVertical: 8, borderRadius: 12, alignItems: 'center', marginBottom: 4, borderWidth: 2, borderColor: 'transparent' },
  railItemOn: { backgroundColor: 'rgba(255,162,31,0.18)' },
  railGlyph: { color: C.text2, fontSize: 18 },
  railLabel: { color: C.text2, fontSize: 10, fontWeight: '600', marginTop: 2 },
  railActive: { color: C.gold },
  content: { flex: 1 },
});

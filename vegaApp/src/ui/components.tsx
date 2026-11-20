// Focusable building blocks for the remote: focused = gold ring + slight scale, like the Samsung/LG/Android TV apps.
import React, { useState } from 'react';
import { ActivityIndicator, Image, StyleSheet, Text, TextInput, TouchableOpacity, View, type StyleProp, type ViewStyle } from 'react-native';
import { C, F, S } from './theme';
import { devLog } from '../devlog';

interface FocusProps {
  onPress?: () => void;
  onLongPress?: () => void;
  onFocus?: () => void;
  preferred?: boolean;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
  focusedStyle?: StyleProp<ViewStyle>;
  children: (focused: boolean) => React.ReactNode;
  testID?: string;
}

/** Base focusable: renders its children with the focus state. */
export function Focusable({ onPress, onLongPress, onFocus, preferred, disabled, style, focusedStyle, children, testID }: FocusProps) {
  const [focused, setFocused] = useState(false);
  return (
    <TouchableOpacity
      activeOpacity={0.85}
      disabled={disabled}
      hasTVPreferredFocus={preferred}
      onPress={onPress}
      onLongPress={onLongPress}
      onFocus={() => { setFocused(true); onFocus?.(); if (testID) devLog(`ui: focus ${testID}`); }}
      onBlur={() => setFocused(false)}
      style={[style, focused && styles.focusRing, focused && focusedStyle]}
      testID={testID}
    >
      {children(focused)}
    </TouchableOpacity>
  );
}

export function Button({ label, onPress, primary, preferred, disabled, style }: {
  label: string; onPress: () => void; primary?: boolean; preferred?: boolean; disabled?: boolean; style?: StyleProp<ViewStyle>;
}) {
  return (
    <Focusable onPress={onPress} preferred={preferred} disabled={disabled} testID={`btn:${label}`}
      style={[styles.button, primary ? styles.buttonPrimary : styles.buttonSecondary, disabled && styles.disabled, style]}>
      {(f) => <Text style={[styles.buttonText, primary && styles.buttonTextPrimary, f && !primary && styles.buttonTextFocused]}>{label}</Text>}
    </Focusable>
  );
}

export function Chip({ label, selected, onPress, onFocus, preferred }: { label: string; selected: boolean; onPress: () => void; onFocus?: () => void; preferred?: boolean }) {
  return (
    <Focusable onPress={onPress} onFocus={onFocus} preferred={preferred} testID={`chip:${label}`} style={[styles.chip, selected && styles.chipSelected]}>
      {() => <Text style={[styles.chipText, selected && styles.chipTextSelected]} numberOfLines={1}>{label}</Text>}
    </Focusable>
  );
}

/** Artwork with initials when there is no image (or it fails), like the TV apps. */
export function Art({ uri, title, style, contain }: { uri: string | null; title: string; style: StyleProp<ViewStyle>; contain?: boolean }) {
  const [failed, setFailed] = useState(false);
  const initials = title.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  return (
    <View style={[styles.art, style]}>
      {uri && !failed
        ? <Image source={{ uri }} style={StyleSheet.absoluteFill} resizeMode={contain ? 'contain' : 'cover'} onError={() => setFailed(true)} />
        : <Text style={styles.initials}>{initials}</Text>}
    </View>
  );
}

export function Poster({ title, subtitle, uri, onPress, onFocus, preferred, width = 110 }: {
  title: string; subtitle?: string | null; uri: string | null; onPress: () => void; onFocus?: () => void; preferred?: boolean; width?: number;
}) {
  return (
    <Focusable onPress={onPress} onFocus={onFocus} preferred={preferred} testID={`poster:${title}`} style={[styles.poster, { width }]} focusedStyle={styles.posterFocused}>
      {() => (
        <>
          <Art uri={uri} title={title} style={{ width, height: width * 1.5 }} />
          <Text style={styles.posterTitle} numberOfLines={1}>{title}</Text>
          {subtitle ? <Text style={styles.posterSub} numberOfLines={1}>{subtitle}</Text> : null}
        </>
      )}
    </Focusable>
  );
}

export function ProgressBar({ value, style }: { value: number; style?: StyleProp<ViewStyle> }) {
  const v = Math.max(0, Math.min(1, value || 0));
  return (
    <View style={[styles.progress, style]}>
      <View style={[styles.progressFill, { width: `${v * 100}%` }]} />
    </View>
  );
}

export function Loading({ label }: { label?: string }) {
  return (
    <View style={styles.center}>
      <ActivityIndicator color={C.accent} size="large" />
      {label ? <Text style={[F.body2, { marginTop: 12 }]}>{label}</Text> : null}
    </View>
  );
}

export function Empty({ title, message }: { title: string; message?: string }) {
  return (
    <View style={styles.center}>
      <Text style={F.title}>{title}</Text>
      {message ? <Text style={[F.body2, { marginTop: 6, textAlign: 'center', maxWidth: 480 }]}>{message}</Text> : null}
    </View>
  );
}

/** Labelled text field; OK opens the TV keyboard. */
export function Field({ label, value, onChange, secure, placeholder, preferred }: {
  label: string; value: string; onChange: (v: string) => void; secure?: boolean; placeholder?: string; preferred?: boolean;
}) {
  const [focused, setFocused] = useState(false);
  return (
    <View style={styles.fieldWrap}>
      <Text style={F.label}>{label}</Text>
      <TextInput
        value={value}
        onChangeText={onChange}
        secureTextEntry={secure}
        placeholder={placeholder}
        placeholderTextColor={C.text2}
        autoCapitalize="none"
        autoCorrect={false}
        hasTVPreferredFocus={preferred}
        onFocus={() => { setFocused(true); devLog(`ui: field focus ${label}`); }}
        onBlur={() => setFocused(false)}
        style={[styles.field, focused && styles.fieldFocused]}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  focusRing: { borderColor: C.gold, borderWidth: 2, transform: [{ scale: 1.04 }] },
  button: { height: 40, paddingHorizontal: 22, borderRadius: 20, alignItems: 'center', justifyContent: 'center', borderWidth: 2, borderColor: 'transparent' },
  buttonPrimary: { backgroundColor: C.accent },
  buttonSecondary: { backgroundColor: C.elevated },
  buttonText: { color: C.text, fontSize: 14, fontWeight: '700' },
  buttonTextPrimary: { color: '#1A1205' },
  buttonTextFocused: { color: C.gold },
  disabled: { opacity: 0.4 },
  chip: { height: 30, paddingHorizontal: 14, borderRadius: 15, backgroundColor: C.elevated, justifyContent: 'center', marginRight: 8, borderWidth: 2, borderColor: 'transparent' },
  chipSelected: { backgroundColor: C.accent },
  chipText: { color: C.text2, fontSize: 12, fontWeight: '600' },
  chipTextSelected: { color: '#1A1205' },
  art: { backgroundColor: C.elevated, borderRadius: 8, overflow: 'hidden', alignItems: 'center', justifyContent: 'center' },
  initials: { color: C.text2, fontSize: 18, fontWeight: '800' },
  poster: { marginRight: 12, marginBottom: 12, borderRadius: 10, padding: 2, borderWidth: 2, borderColor: 'transparent' },
  posterFocused: { backgroundColor: C.elevated },
  posterTitle: { color: C.text, fontSize: 12, fontWeight: '600', marginTop: 6 },
  posterSub: { color: C.text2, fontSize: 11 },
  progress: { height: 4, borderRadius: 2, backgroundColor: 'rgba(255,255,255,0.2)', overflow: 'hidden' },
  progressFill: { height: '100%', backgroundColor: C.accent },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: S.margin },
  fieldWrap: { marginBottom: 10 },
  field: { height: 40, borderRadius: 10, backgroundColor: C.surface, color: C.text, paddingHorizontal: 14, fontSize: 14, marginTop: 4, borderWidth: 2, borderColor: C.elevated },
  fieldFocused: { borderColor: C.gold },
});

// Port of xtream/Flex.kt. API Xtream servers are notoriously inconsistent: the same field can be a
// number, a numeric string, an empty string, null, false, or even an array depending on the
// server version. These helpers accept anything and never throw.
import { toDoubleOrNull, toIntOrNull } from '../util/text.ts';

export type Json = unknown;

export function flexString(v: Json): string | null {
  if (v == null) return null;
  if (typeof v === 'string') return v.trim() !== '' && v !== 'null' ? v : null;
  if (typeof v === 'number') return isFinite(v) ? String(v) : null;
  if (typeof v === 'boolean') return v ? 'true' : 'false';
  if (Array.isArray(v)) return v.length > 0 ? flexString(v[0]) : null;
  return null; // object
}

export function flexLong(v: Json): number | null {
  const s = flexString(v);
  if (s == null) return null;
  const l = toIntOrNull(s);
  if (l != null) return l;
  const d = toDoubleOrNull(s);
  if (d == null) return null;
  if (isNaN(d)) return 0;
  return Math.trunc(d);
}

/** Out-of-range values (garbage from some servers) become null instead of silently wrapping around. */
export function flexInt(v: Json): number | null {
  const l = flexLong(v);
  return l != null && l >= -2147483648 && l <= 2147483647 ? l : null;
}

export function flexDouble(v: Json): number | null {
  const s = flexString(v);
  return s == null ? null : toDoubleOrNull(s.replace(/,/g, '.'));
}

export function flexBool(v: Json): boolean {
  const s0 = flexString(v);
  if (s0 == null) return false;
  const s = s0.toLowerCase();
  if (s === '1' || s === 'true' || s === 'yes') return true;
  const d = toDoubleOrNull(s);
  return d != null ? d !== 0 : false;
}

/** `JsonElement?.get(key)`: property of an object, else undefined. */
export function field(v: Json, key: string): Json {
  if (v != null && typeof v === 'object' && !Array.isArray(v)) return (v as { [k: string]: Json })[key];
  return undefined;
}

export function isObject(v: Json): v is { [k: string]: Json } {
  return v != null && typeof v === 'object' && !Array.isArray(v);
}

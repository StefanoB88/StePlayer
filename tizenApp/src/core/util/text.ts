// Port of util/Text.kt.

const FOLD: { [code: number]: string } = {};
function fold(chars: string, to: string): void {
  for (let i = 0; i < chars.length; i++) FOLD[chars.charCodeAt(i)] = to;
}
fold('àáâãäåā', 'a');
fold('èéêëēėę', 'e');
fold('ìíîïī', 'i');
fold('òóôõöøō', 'o');
fold('ùúûüū', 'u');
fold('çćč', 'c');
fold('ñń', 'n');
fold('ßšś', 's');
fold('žźż', 'z');
fold('ýÿ', 'y');

/** ASCII characters folded to a space: tab, LF, CR, '_', '.', '-', '|', ':' (and the space itself). */
function isAsciiSeparator(code: number): boolean {
  return code === 32 || code === 9 || code === 10 || code === 13 || code === 95 || code === 46 || code === 45 ||
    code === 124 || code === 58;
}

/** Lowercase, accent-free, single-spaced version of a title used for fast search. */
export function normalizeForSearch(input: string): string {
  let lower = input.toLowerCase();
  // Kotlin lowercases char by char: a few characters ("İ") expand in JS, keep those as they are.
  if (lower.length !== input.length) {
    let s = '';
    for (let i = 0; i < input.length; i++) {
      const l = input.charAt(i).toLowerCase();
      s += l.length === 1 ? l : input.charAt(i);
    }
    lower = s;
  }
  let out = '';
  let lastSpace = true;
  for (let i = 0; i < lower.length; i++) {
    const code = lower.charCodeAt(i);
    let mapped: string;
    if (code < 128) {
      mapped = isAsciiSeparator(code) ? ' ' : lower.charAt(i);
    } else {
      mapped = FOLD[code] || lower.charAt(i);
    }
    if (mapped === ' ') {
      if (!lastSpace) out += ' ';
      lastSpace = true;
    } else {
      out += mapped;
      lastSpace = false;
    }
  }
  return out.length > 0 && out.charCodeAt(out.length - 1) === 32 ? out.substring(0, out.length - 1) : out;
}

const YEAR_REGEX = /(19|20)\d{2}/;

/** Extracts a 4 digit year from strings like "2019-05-01", "2019" or "Film (2019)". */
export function extractYear(input: string | null | undefined): number | null {
  if (input == null || input.trim() === '') return null;
  const m = YEAR_REGEX.exec(input);
  return m ? parseInt(m[0], 10) : null;
}

/**
 * Stable, platform independent 64-bit FNV-1a hash rendered as hex (same output as Kotlin's
 * `stableHash`). 64-bit math without BigInt: four 16-bit limbs.
 */
export function stableHash(input: string): string {
  // offset basis 0xcbf29ce484222325
  let h0 = 0x2325, h1 = 0x8422, h2 = 0x9ce4, h3 = 0xcbf2;
  for (let i = 0; i < input.length; i++) {
    h0 ^= input.charCodeAt(i);
    // * 0x100000001b3 = * 0x1b3 + << 40
    let r0 = h0 * 0x1b3;
    let r1 = h1 * 0x1b3 + (r0 >>> 16);
    r0 &= 0xffff;
    let r2 = h2 * 0x1b3 + h0 * 256 + (r1 >>> 16);
    r1 &= 0xffff;
    const r3 = h3 * 0x1b3 + h1 * 256 + (r2 >>> 16);
    r2 &= 0xffff;
    h0 = r0; h1 = r1; h2 = r2; h3 = r3 & 0xffff;
  }
  const hex = hex4(h3) + hex4(h2) + hex4(h1) + hex4(h0);
  let i = 0;
  while (i < hex.length - 1 && hex.charCodeAt(i) === 48) i++;
  return hex.substring(i);
}

function hex4(n: number): string {
  const s = n.toString(16);
  return '0000'.substring(s.length) + s;
}

/** Masks credentials so URLs can be shown in error messages without leaking them. */
export function redactUrl(url: string): string {
  return url.replace(/(username|password)=[^&]*/g, '$1=***')
    .replace(/\/(live|movie|series|timeshift)\/[^/]+\/[^/]+\//g, '/$1/***/***/');
}

/** Kotlin `String.toIntOrNull()` / `toLongOrNull()` (decimal, optional sign). */
export function toIntOrNull(s: string | null | undefined): number | null {
  if (s == null || !/^[+-]?\d+$/.test(s)) return null;
  const n = parseInt(s, 10);
  return isFinite(n) ? n : null;
}

/** Kotlin `toIntOrNull()` limited to the 32-bit range. */
export function toInt32OrNull(s: string | null | undefined): number | null {
  const n = toIntOrNull(s);
  return n != null && n >= -2147483648 && n <= 2147483647 ? n : null;
}

/** Kotlin `String.toDoubleOrNull()`. */
export function toDoubleOrNull(s: string | null | undefined): number | null {
  if (s == null) return null;
  const t = s.trim();
  if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?[dDfF]?$/.test(t) && !/^[+-]?(NaN|Infinity)$/.test(t)) return null;
  const n = Number(t.replace(/[dDfF]$/, ''));
  return n;
}

export function isBlank(s: string | null | undefined): boolean {
  return s == null || s.trim() === '';
}

/** `takeIf { it.isNotBlank() }`. */
export function nonBlank(s: string | null | undefined): string | null {
  return s == null || s.trim() === '' ? null : s;
}

/** `substringBefore(delimiter)`. */
export function substringBefore(s: string, delimiter: string): string {
  const i = s.indexOf(delimiter);
  return i < 0 ? s : s.substring(0, i);
}

/** `substringAfter(delimiter, missing = s)`. */
export function substringAfter(s: string, delimiter: string, missing: string = s): string {
  const i = s.indexOf(delimiter);
  return i < 0 ? missing : s.substring(i + delimiter.length);
}

/** `substringAfterLast(delimiter, missing = s)`. */
export function substringAfterLast(s: string, delimiter: string, missing: string = s): string {
  const i = s.lastIndexOf(delimiter);
  return i < 0 ? missing : s.substring(i + delimiter.length);
}

/** Lowercase extension of the URL path ("ts", "m3u8"…) when it looks like one (2-4 chars). */
export function extensionOf(url: string): string | null {
  const ext = substringAfterLast(substringAfterLast(substringBefore(url, '?'), '/'), '.', '').toLowerCase();
  return ext.length >= 2 && ext.length <= 4 ? ext : null;
}

/** Stable sort (Chromium 56's Array.prototype.sort is not stable for more than 10 elements). */
export function stableSort<T>(items: readonly T[], compare: (a: T, b: T) => number): T[] {
  const indexed = items.map((item, index) => ({ item, index }));
  indexed.sort((a, b) => compare(a.item, b.item) || a.index - b.index);
  return indexed.map((x) => x.item);
}

/** Kotlin String.compareTo (UTF-16 code units, not locale aware). */
export function compareStrings(a: string, b: string): number {
  return a < b ? -1 : a > b ? 1 : 0;
}

export function clamp(v: number, min: number, max: number): number {
  return v < min ? min : v > max ? max : v;
}

export function pad2(n: number): string {
  return n < 10 ? '0' + n : String(n);
}

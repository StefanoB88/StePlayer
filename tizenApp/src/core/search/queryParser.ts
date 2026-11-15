// Port of search/QueryParser.kt.
import { normalizeForSearch } from '../util/text.ts';

const STOP_WORDS = new Set<string>([
  'il', 'lo', 'la', 'i', 'gli', 'le', 'l', 'un', 'una', 'uno', 'di', 'del', 'della', 'dello', 'dei',
  'degli', 'delle', 'da', 'dal', 'dalla', 'in', 'nel', 'nella', 'con', 'su', 'sul', 'sulla', 'per',
  'tra', 'fra', 'e', 'ed', 'o', 'a', 'al', 'alla', 'ai', 'agli', 'alle', 'che',
  'the', 'of', 'and', 'an', 'on', 'to', 'at', 'for',
]);

function isAsciiAlnum(c: number): boolean {
  return (c >= 48 && c <= 57) || (c >= 65 && c <= 90) || (c >= 97 && c <= 122);
}

/** Splits an already normalised text like SQLite's "simple" tokenizer (ASCII non-alphanumerics separate). */
export function tokenizeNormalized(norm: string): string[] {
  const out: string[] = [];
  let start = -1;
  for (let i = 0; i < norm.length; i++) {
    const c = norm.charCodeAt(i);
    const separator = c < 128 && !isAsciiAlnum(c);
    if (separator) {
      if (start >= 0) { out.push(norm.substring(start, i)); start = -1; }
    } else if (start < 0) {
      start = i;
    }
  }
  if (start >= 0) out.push(norm.substring(start));
  return out;
}

/**
 * Turns free text into search terms:
 * - accent/case-insensitive (same normalisation used when indexing)
 * - Italian/English stop-words dropped ("il", "di", "the"...) unless they're all the user typed
 * - light Italian stemming: "napoli" → prefix "napol" so it also finds "napoletano", "napoletana"
 */
export const QueryParser = {
  tokenize(text: string): string[] {
    return tokenizeNormalized(normalizeForSearch(text));
  },

  meaningfulTokens(text: string): string[] {
    const all = QueryParser.tokenize(text);
    const filtered = all.filter((t) => !STOP_WORDS.has(t));
    const list = filtered.length > 0 ? filtered : all;
    return list.filter((t, i) => list.indexOf(t) === i);
  },

  /** The prefix actually matched for one token. */
  stem(token: string): string {
    if (token.length >= 5 && 'aeiou'.indexOf(token.charAt(token.length - 1)) >= 0 && /^[\x00-\x7f]*$/.test(token)) {
      return token.substring(0, token.length - 1);
    }
    return token;
  },

  /** FTS-style expression (kept for parity with Kotlin: implicit AND, prefix match on each term). */
  ftsExpression(tokens: string[]): string {
    return tokens.map((t) => QueryParser.stem(t) + '*').join(' ');
  },
};

/** Bounded Damerau–Levenshtein (optimal string alignment) distance. Returns max+1 if exceeded. */
export function editDistance(a: string, b: string, max: number): number {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  const n = a.length;
  const m = b.length;
  let prev2 = new Int32Array(m + 1);
  let prev = new Int32Array(m + 1);
  for (let j = 0; j <= m; j++) prev[j] = j;
  let cur = new Int32Array(m + 1);
  for (let i = 1; i <= n; i++) {
    cur[0] = i;
    let rowMin = cur[0];
    for (let j = 1; j <= m; j++) {
      const cost = a.charCodeAt(i - 1) === b.charCodeAt(j - 1) ? 0 : 1;
      let v = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
      if (i > 1 && j > 1 && a.charCodeAt(i - 1) === b.charCodeAt(j - 2) && a.charCodeAt(i - 2) === b.charCodeAt(j - 1)) {
        v = Math.min(v, prev2[j - 2] + 1);
      }
      cur[j] = v;
      if (v < rowMin) rowMin = v;
    }
    if (rowMin > max) return max + 1;
    const t = prev2; prev2 = prev; prev = cur; cur = t;
  }
  return prev[m];
}

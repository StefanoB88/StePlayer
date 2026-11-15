// APIs newer than Chromium 56 (Tizen 4.0) used by the app or the core. esbuild lowers syntax, not APIs.
/* eslint-disable no-extend-native */
const sp = String.prototype as unknown as Record<string, unknown>;
if (!sp.padStart) {
  sp.padStart = function (this: string, len: number, fill = ' ') {
    let s = String(this);
    if (s.length >= len || !fill) return s;
    while (s.length < len) s = fill + s;
    return s.slice(s.length - len);
  };
}
if (!sp.padEnd) {
  sp.padEnd = function (this: string, len: number, fill = ' ') {
    let s = String(this);
    if (s.length >= len || !fill) return s;
    while (s.length < len) s += fill;
    return s.slice(0, len);
  };
}
if (!sp.replaceAll) {
  sp.replaceAll = function (this: string, search: string | RegExp, replacement: string) {
    if (search instanceof RegExp) return this.replace(search, replacement);
    return this.split(search).join(replacement);
  };
}
const ap = Array.prototype as unknown as Record<string, unknown>;
if (!ap.flat) {
  ap.flat = function (this: unknown[], depth = 1): unknown[] {
    const out: unknown[] = [];
    const walk = (arr: unknown[], d: number) => {
      for (const v of arr) {
        if (Array.isArray(v) && d > 0) walk(v, d - 1);
        else out.push(v);
      }
    };
    walk(this, depth);
    return out;
  };
}
if (!ap.flatMap) {
  ap.flatMap = function (this: unknown[], fn: (v: unknown, i: number, a: unknown[]) => unknown, thisArg?: unknown) {
    return (this.map(fn, thisArg) as unknown as { flat(d: number): unknown[] }).flat(1);
  };
}
if (!ap.at) {
  ap.at = function (this: unknown[], i: number) {
    const n = Math.trunc(i) || 0;
    return this[n < 0 ? this.length + n : n];
  };
}
const oc = Object as unknown as Record<string, unknown>;
if (!oc.fromEntries) {
  oc.fromEntries = function (entries: Iterable<[PropertyKey, unknown]>) {
    const o: Record<PropertyKey, unknown> = {};
    for (const [k, v] of entries) o[k] = v;
    return o;
  };
}
const pc = Promise as unknown as Record<string, unknown>;
if (!pc.allSettled) {
  pc.allSettled = function (ps: Iterable<unknown>) {
    return Promise.all(Array.from(ps, (p) => Promise.resolve(p).then(
      (value) => ({ status: 'fulfilled', value }),
      (reason) => ({ status: 'rejected', reason }),
    )));
  };
}
if (typeof (globalThis as unknown) === 'undefined') {
  (window as unknown as Record<string, unknown>).globalThis = window;
}
export {};

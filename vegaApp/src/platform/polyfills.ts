// Web APIs the shared core (../tizenApp/src/core) expects and Hermes on Vega lacks or only partly implements.
// Imported first, before anything that loads the core.
// Full WHATWG URL (React Native's own lacks hostname, port, pathname, searchParams). Not react-native-url-polyfill:
// its setup queries NativeModules.BlobModule.getConstants(), which crashes the app on Vega.
import { URL as WhatwgURL, URLSearchParams as WhatwgURLSearchParams } from 'whatwg-url-without-unicode';

/* eslint-disable @typescript-eslint/no-explicit-any */
const g = globalThis as any;
// hls.js turns its MediaSource into an object URL; the Vega player recognises "blob:///<id>?offset=0&size=0"
// (react-native-w3cmedia MediaPlayer.setSrcWithCustomHeaders) and finds the MediaSource by id.
(WhatwgURL as any).createObjectURL = (blob: any) =>
  `blob:///${blob && blob.data ? blob.data.blobId : 0}?offset=${blob && blob.data ? blob.data.offset || 0 : 0}&size=${blob && blob.size ? blob.size : 0}`;
(WhatwgURL as any).revokeObjectURL = () => undefined;
g.URL = WhatwgURL;
g.URLSearchParams = WhatwgURLSearchParams;

// ----- clearTimeout / clearInterval: on Vega they throw "called with an invalid handle" for undefined or
// already-finished timers; browsers ignore those. hls.js clears unset timers all the time (inside promises, where the
// exception silently stopped playback).
for (const name of ['clearTimeout', 'clearInterval'] as const) {
  const original = g[name];
  if (typeof original === 'function' && !original.__steTolerant) {
    const tolerant = (handle: unknown) => {
      if (handle == null) return;
      try { original(handle); } catch { /* invalid or finished timer: nothing to clear */ }
    };
    (tolerant as any).__steTolerant = true;
    g[name] = tolerant;
  }
}

// ----- DOMException (fake-indexeddb's errors extend it; Hermes has none).
if (typeof g.DOMException !== 'function') {
  g.DOMException = class DOMException extends Error {
    constructor(message = '', name = 'Error') {
      super(message);
      this.name = name;
    }
  };
}

// ----- structuredClone (fake-indexeddb clones every stored value). The core stores plain data: objects, arrays,
// primitives, Date, Uint8Array, Map/Set.
if (typeof g.structuredClone !== 'function') {
  const clone = (v: any, seen: Map<any, any>): any => {
    if (v === null || typeof v !== 'object') return v;
    if (seen.has(v)) return seen.get(v);
    if (v instanceof Date) return new Date(v.getTime());
    if (ArrayBuffer.isView(v)) return (v as any).slice();
    if (v instanceof ArrayBuffer) return v.slice(0);
    if (v instanceof Map) { const m = new Map(); seen.set(v, m); v.forEach((x, k) => m.set(clone(k, seen), clone(x, seen))); return m; }
    if (v instanceof Set) { const s = new Set(); seen.set(v, s); v.forEach((x) => s.add(clone(x, seen))); return s; }
    const out: any = Array.isArray(v) ? [] : {};
    seen.set(v, out);
    for (const k of Object.keys(v)) out[k] = clone(v[k], seen);
    return out;
  };
  g.structuredClone = (v: any) => clone(v, new Map());
}

// ----- TextEncoder / TextDecoder (UTF-8 only, with { stream: true } like the browser's).
if (typeof g.TextEncoder !== 'function') {
  g.TextEncoder = class {
    readonly encoding = 'utf-8';
    encode(s = ''): Uint8Array {
      const out: number[] = [];
      for (let i = 0; i < s.length; i++) {
        let c = s.charCodeAt(i);
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < s.length) {
          const d = s.charCodeAt(i + 1);
          if (d >= 0xdc00 && d <= 0xdfff) { c = 0x10000 + ((c - 0xd800) << 10) + (d - 0xdc00); i++; }
        }
        if (c < 0x80) out.push(c);
        else if (c < 0x800) out.push(0xc0 | (c >> 6), 0x80 | (c & 63));
        else if (c < 0x10000) out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
        else out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 63), 0x80 | ((c >> 6) & 63), 0x80 | (c & 63));
      }
      return new Uint8Array(out);
    }
  };
}
if (typeof g.TextDecoder !== 'function') {
  g.TextDecoder = class {
    readonly encoding = 'utf-8';
    private pending: number[] = []; // bytes of a character split across chunks
    decode(input?: ArrayBufferView | ArrayBuffer, options?: { stream?: boolean }): string {
      const bytes = input == null ? new Uint8Array(0)
        : input instanceof ArrayBuffer ? new Uint8Array(input)
        : new Uint8Array(input.buffer, input.byteOffset, input.byteLength);
      const all = this.pending.length ? Uint8Array.from([...this.pending, ...bytes]) : bytes;
      this.pending = [];
      let end = all.length;
      if (options && options.stream) {
        // Keep an incomplete trailing sequence for the next call.
        let i = all.length - 1;
        let back = 0;
        while (i >= 0 && back < 4 && (all[i] & 0xc0) === 0x80) { i--; back++; }
        if (i >= 0) {
          const b = all[i];
          const need = b >= 0xf0 ? 4 : b >= 0xe0 ? 3 : b >= 0xc0 ? 2 : 1;
          if (need > back + 1) { end = i; this.pending = Array.from(all.subarray(i)); }
        }
      }
      let s = '';
      const parts: string[] = [];
      for (let i = 0; i < end;) {
        const b = all[i];
        let c: number;
        if (b < 0x80) { c = b; i += 1; }
        else if (b >= 0xc0 && b < 0xe0 && i + 1 < end) { c = ((b & 31) << 6) | (all[i + 1] & 63); i += 2; }
        else if (b >= 0xe0 && b < 0xf0 && i + 2 < end) { c = ((b & 15) << 12) | ((all[i + 1] & 63) << 6) | (all[i + 2] & 63); i += 3; }
        else if (b >= 0xf0 && i + 3 < end) { c = ((b & 7) << 18) | ((all[i + 1] & 63) << 12) | ((all[i + 2] & 63) << 6) | (all[i + 3] & 63); i += 4; }
        else { c = 0xfffd; i += 1; }
        if (c > 0xffff) { c -= 0x10000; s += String.fromCharCode(0xd800 + (c >> 10), 0xdc00 + (c & 1023)); }
        else s += String.fromCharCode(c);
        if (s.length > 8192) { parts.push(s); s = ''; }
      }
      parts.push(s);
      return parts.join('');
    }
  };
}

export {};

// Test helpers: in-memory IndexedDB (fake-indexeddb) + scriptable fake HTTP server.
import 'fake-indexeddb/auto';
import { IDBFactory } from 'fake-indexeddb';
import { gzipSync } from 'node:zlib';
import type { Core } from '../src/core/api.ts';
import type { CoreOptions, Http, HttpResponse } from '../src/core/index.ts';
import { createCore } from '../src/core/index.ts';

export interface FakeReply {
  status?: number;
  body?: string | Uint8Array;
  /** Body split in chunks of this many bytes (default 7: exercises every boundary). */
  chunk?: number;
  /** Body produced lazily, chunk by chunk (never fully in memory). */
  stream?: () => Iterator<Uint8Array>;
}

export type FakeHandler = (url: URL) => FakeReply | Promise<FakeReply>;

export interface FakeHttp extends Http {
  requests: string[];
}

export function fakeHttp(handler: FakeHandler): FakeHttp {
  const requests: string[] = [];
  return {
    requests,
    async get(url: string): Promise<HttpResponse> {
      requests.push(url);
      let reply: FakeReply;
      try {
        reply = await handler(new URL(url));
      } catch (e) {
        throw e instanceof Error ? e : new Error(String(e));
      }
      const status = reply.status ?? 200;
      let iter: Iterator<Uint8Array>;
      if (reply.stream) {
        iter = reply.stream();
      } else {
        const bytes = typeof reply.body === 'string' ? new TextEncoder().encode(reply.body) : (reply.body ?? new Uint8Array(0));
        const size = reply.chunk ?? 7;
        let off = 0;
        iter = {
          next(): IteratorResult<Uint8Array> {
            if (off >= bytes.length) return { done: true, value: undefined };
            const part = bytes.subarray(off, Math.min(bytes.length, off + size));
            off += size;
            return { done: false, value: part };
          },
        };
      }
      let cancelled = false;
      return {
        status,
        async read() {
          if (cancelled) return null;
          const r = iter.next();
          return r.done ? null : r.value;
        },
        cancel() { cancelled = true; },
      };
    },
  };
}

let counter = 0;

export async function newCore(http: Http, options: Partial<CoreOptions> = {}): Promise<Core> {
  counter++;
  return createCore({ http, indexedDB: new IDBFactory(), dbName: `steplayer-test-${counter}`, ...options });
}

export function gzip(s: string): Uint8Array {
  return new Uint8Array(gzipSync(Buffer.from(s, 'utf8')));
}

/** "yyyyMMddHHmmss +0000" for an epoch in seconds. */
export function xmltvTime(epochSec: number): string {
  const d = new Date(epochSec * 1000);
  const p = (n: number) => (n < 10 ? '0' : '') + n;
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())} +0000`;
}

export function utcMs(y: number, m: number, d: number, h: number, min = 0): number {
  return Date.UTC(y, m - 1, d, h, min, 0);
}

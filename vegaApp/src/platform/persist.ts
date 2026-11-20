// Makes the in-memory IndexedDB (fake-indexeddb) persistent on Vega: every object store is saved as one JSON file in
// the app's writable /data folder, shortly after a read-write transaction touches it, and loaded back at start-up
// before the core is used. Lists, catalog, guide, favourites and progress survive restarts like on the other TVs.
import { KeplerFileSystem as FileSystem } from '@amazon-devices/kepler-file-system';

/* eslint-disable @typescript-eslint/no-explicit-any */
// React Native's TypeScript setup has no DOM lib: the IndexedDB objects (fake-indexeddb) are typed loosely here.
type IDBDatabase = any;
type IDBRequest<T> = { result: T; error: unknown; onsuccess: (() => void) | null; onerror: (() => void) | null };
type IDBTransaction = any;
type IDBTransactionMode = 'readonly' | 'readwrite' | 'versionchange';
type IDBValidKey = any;

const SAVE_DELAY_MS = 1500;

/** One file per store in the app's persistent folder (the file API has no mkdir: no subfolder). */
const fileOf = (store: string) => `/data/db-${store}.json`;

let db: IDBDatabase | null = null;
/** Development diagnostics (saves, loads, failures). */
let report: (msg: string) => void = () => undefined;
export function setPersistLogger(fn: (msg: string) => void): void { report = fn; }
let restoring = false;
const dirty = new Set<string>();
let timer: ReturnType<typeof setTimeout> | null = null;

function request<T>(r: IDBRequest<T>): Promise<T> {
  return new Promise((resolve, reject) => { r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
}

/** Dumps one store: [keys, values] (compound keys are arrays, fine in JSON). */
async function saveStore(store: string): Promise<void> {
  if (!db) return;
  const tx = db.transaction(store, 'readonly');
  const os = tx.objectStore(store);
  const [keys, values] = await Promise.all([request<unknown[]>(os.getAllKeys()), request<unknown[]>(os.getAll())]);
  const json = os.keyPath == null ? JSON.stringify([keys, values]) : JSON.stringify([null, values]);
  await FileSystem.writeStringToFile(fileOf(store), json, 'UTF-8');
  report(`saved ${store}: ${values.length} records, ${json.length} chars`);
}

function scheduleSave(): void {
  if (timer) return;
  timer = setTimeout(async () => {
    timer = null;
    const stores = Array.from(dirty);
    dirty.clear();
    for (const s of stores) {
      try { await saveStore(s); } catch (e) { dirty.add(s); report(`save ${s} failed: ${String(e)}`); } // retried with the next change
    }
  }, SAVE_DELAY_MS);
}

/** Loads the saved stores into `database` (called once, right after the core created its schema). */
async function restore(database: IDBDatabase): Promise<void> {
  restoring = true;
  try {
    for (const store of Array.from(database.objectStoreNames as ArrayLike<string>)) {
      let text: string;
      try { text = await FileSystem.readFileAsString(fileOf(store), 'UTF-8'); } catch (e) { report(`load ${store}: ${String(e)}`); continue; } // never saved
      let data: [unknown[] | null, unknown[]];
      try { data = JSON.parse(text); } catch { continue; }
      const [keys, values] = data;
      if (!values || !values.length) continue;
      const tx = database.transaction(store, 'readwrite');
      const os = tx.objectStore(store);
      values.forEach((v, i) => { if (keys) os.put(v, keys[i] as IDBValidKey); else os.put(v); });
      await new Promise<void>((resolve, reject) => { tx.oncomplete = () => resolve(); tx.onerror = () => reject(tx.error); });
      report(`restored ${store}: ${values.length} records`);
    }
  } finally {
    restoring = false;
  }
}

/**
 * Hooks the IndexedDB implementation so read-write transactions mark their stores for saving, then restores the
 * saved data. Call after `createCore` (schema created) and before the UI reads anything.
 */
export async function persistIndexedDb(dbName: string, version: number): Promise<void> {
  const g = globalThis as any;
  const proto = g.IDBDatabase && g.IDBDatabase.prototype;
  if (proto && !proto.__stePersist) {
    const original = proto.transaction;
    proto.transaction = function (this: IDBDatabase, names: string | string[], mode?: IDBTransactionMode, ...rest: unknown[]) {
      const tx: IDBTransaction = original.call(this, names, mode, ...rest);
      if (mode === 'readwrite' && !restoring && this.name === dbName) {
        const list = typeof names === 'string' ? [names] : Array.from(names);
        tx.addEventListener('complete', () => { list.forEach((n) => dirty.add(n)); scheduleSave(); });
      }
      return tx;
    };
    proto.__stePersist = true;
  }
  db = await new Promise<IDBDatabase>((resolve, reject) => {
    const r = g.indexedDB.open(dbName, version);
    r.onsuccess = () => resolve(r.result);
    r.onerror = () => reject(r.error);
  });
  await restore(db);
}

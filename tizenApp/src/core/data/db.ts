// Small IndexedDB wrapper (Kotlin used SQLDelight). Requests are issued synchronously inside a
// transaction and results are delivered when the transaction completes, so the code never relies
// on keeping a transaction alive across promise continuations (fragile on older engines).

export const DB_NAME = 'steplayer';
const DB_VERSION = 1;

export const S = {
  sources: 'sources',
  secrets: 'secrets',
  settings: 'settings',
  categories: 'categories',
  categoryPrefs: 'categoryPrefs',
  channels: 'channels',
  movies: 'movies',
  series: 'series',
  episodes: 'episodes',
  epg: 'epg',
  favorites: 'favorites',
  progress: 'progress',
  search: 'search',
} as const;

export type StoreName = (typeof S)[keyof typeof S];

/** Catalog stores are "slotted": a sync writes the inactive slot, then flips (atomic swap). */
export const CATALOG_STORES: StoreName[] = [S.categories, S.channels, S.movies, S.series, S.episodes];

function upgrade(db: IDBDatabase): void {
  const names = db.objectStoreNames;
  const create = (name: string, opts?: IDBObjectStoreParameters): IDBObjectStore | null =>
    names.contains(name) ? null : db.createObjectStore(name, opts);

  create(S.sources, { keyPath: 'id', autoIncrement: true });
  create(S.secrets);
  create(S.settings);

  const cat = create(S.categories, { keyPath: ['s', 'g', 't', 'id'] });
  if (cat) cat.createIndex('pos', ['s', 'g', 't', 'pos']);
  create(S.categoryPrefs, { keyPath: ['s', 't', 'id'] });

  const ch = create(S.channels, { keyPath: ['s', 'g', 'id'] });
  if (ch) {
    ch.createIndex('pos', ['s', 'g', 'pos']);
    ch.createIndex('cat', ['s', 'g', 'cat', 'pos']);
    ch.createIndex('num', ['s', 'g', 'num', 'pos']);
    ch.createIndex('epg', ['s', 'g', 'epg']);
  }
  const mv = create(S.movies, { keyPath: ['s', 'g', 'id'] });
  if (mv) {
    mv.createIndex('pos', ['s', 'g', 'pos']);
    mv.createIndex('cat', ['s', 'g', 'cat', 'pos']);
    mv.createIndex('added', ['s', 'g', 'added']);
  }
  const se = create(S.series, { keyPath: ['s', 'g', 'id'] });
  if (se) {
    se.createIndex('pos', ['s', 'g', 'pos']);
    se.createIndex('cat', ['s', 'g', 'cat', 'pos']);
    se.createIndex('modified', ['s', 'g', 'modified']);
  }
  const ep = create(S.episodes, { keyPath: ['s', 'g', 'id'] });
  if (ep) ep.createIndex('series', ['s', 'g', 'series', 'season', 'number']);

  create(S.epg, { keyPath: ['s', 'c', 'd'] }); // see epgBuckets.ts
  create(S.favorites, { keyPath: ['s', 't', 'id'] });
  const pr = create(S.progress, { keyPath: ['s', 't', 'id'] });
  if (pr) pr.createIndex('updated', ['s', 'updatedAt']);
  create(S.search);
}

export function openDb(factory: IDBFactory, name: string = DB_NAME): Promise<Db> {
  return new Promise((resolve, reject) => {
    const req = factory.open(name, DB_VERSION);
    req.onupgradeneeded = () => upgrade(req.result);
    req.onsuccess = () => resolve(new Db(req.result));
    req.onerror = () => reject(req.error ?? new Error('Impossibile aprire il database'));
    req.onblocked = () => reject(new Error('Database bloccato da un\'altra scheda'));
  });
}

/** Key range helpers. Arrays sort after every number/string, so `[...prefix, []]` closes a prefix. */
export function prefixRange(prefix: IDBValidKey[]): IDBKeyRange {
  return IDBKeyRange.bound(prefix, prefix.concat([[]]));
}

export function boundRange(lower: IDBValidKey[], upper: IDBValidKey[], lowerOpen = false, upperOpen = false): IDBKeyRange {
  return IDBKeyRange.bound(lower, upper, lowerOpen, upperOpen);
}

type Source = IDBObjectStore | IDBIndex;

export class Db {
  readonly idb: IDBDatabase;

  constructor(idb: IDBDatabase) {
    this.idb = idb;
  }

  /**
   * Runs [body] in one transaction. [body] issues requests synchronously (and may chain more from
   * request callbacks); the returned getter is evaluated once the transaction has committed.
   */
  run<T>(stores: StoreName | StoreName[], mode: IDBTransactionMode, body: (tx: IDBTransaction) => () => T): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      let tx: IDBTransaction;
      try {
        tx = this.idb.transaction(stores, mode);
      } catch (e) {
        reject(e);
        return;
      }
      let getter: () => T;
      try {
        getter = body(tx);
      } catch (e) {
        try { tx.abort(); } catch { /* already finished */ }
        reject(e);
        return;
      }
      tx.oncomplete = () => {
        try { resolve(getter()); } catch (e) { reject(e); }
      };
      tx.onerror = () => reject(tx.error ?? new Error('Errore del database'));
      tx.onabort = () => reject(tx.error ?? new Error('Operazione sul database annullata'));
    });
  }

  get<T>(store: StoreName, key: IDBValidKey): Promise<T | undefined> {
    return this.run(store, 'readonly', (tx) => {
      let v: T | undefined;
      const r = tx.objectStore(store).get(key);
      r.onsuccess = () => { v = r.result as T | undefined; };
      return () => v;
    });
  }

  getMany<T>(store: StoreName, keys: IDBValidKey[], index?: string): Promise<(T | undefined)[]> {
    if (keys.length === 0) return Promise.resolve([]);
    return this.run(store, 'readonly', (tx) => {
      const os = tx.objectStore(store);
      const src: Source = index ? os.index(index) : os;
      const out: (T | undefined)[] = new Array(keys.length);
      keys.forEach((k, i) => {
        const r = src.get(k);
        r.onsuccess = () => { out[i] = r.result as T | undefined; };
      });
      return () => out;
    });
  }

  getAll<T>(store: StoreName, range?: IDBKeyRange | IDBValidKey | null, count?: number, index?: string): Promise<T[]> {
    return this.run(store, 'readonly', (tx) => {
      const os = tx.objectStore(store);
      const src: Source = index ? os.index(index) : os;
      let v: T[] = [];
      const r = count != null ? src.getAll(range ?? null, count) : src.getAll(range ?? null);
      r.onsuccess = () => { v = r.result as T[]; };
      return () => v;
    });
  }

  /** getAll for many ranges in one transaction (results in the same order). */
  getAllMany<T>(store: StoreName, ranges: IDBKeyRange[], index?: string): Promise<T[][]> {
    if (ranges.length === 0) return Promise.resolve([]);
    return this.run(store, 'readonly', (tx) => {
      const os = tx.objectStore(store);
      const src: Source = index ? os.index(index) : os;
      const out: T[][] = new Array(ranges.length);
      ranges.forEach((range, i) => {
        const r = src.getAll(range);
        r.onsuccess = () => { out[i] = r.result as T[]; };
      });
      return () => out;
    });
  }

  /**
   * Walks a cursor: skips [offset] records, then calls [visit] until it returns false or [limit]
   * values were accepted (visit returns true to accept/continue, false to stop).
   */
  scan<T>(
    store: StoreName, range: IDBKeyRange | null, options: { index?: string; direction?: IDBCursorDirection; offset?: number },
    visit: (value: T) => boolean,
  ): Promise<void> {
    return this.run(store, 'readonly', (tx) => {
      const os = tx.objectStore(store);
      const src: Source = options.index ? os.index(options.index) : os;
      const req = src.openCursor(range, options.direction ?? 'next');
      let skipped = (options.offset ?? 0) <= 0;
      req.onsuccess = () => {
        const cursor = req.result;
        if (!cursor) return;
        if (!skipped) {
          skipped = true;
          cursor.advance(options.offset as number);
          return;
        }
        if (visit(cursor.value as T)) cursor.continue();
      };
      return () => undefined;
    });
  }

  put(store: StoreName, value: unknown, key?: IDBValidKey): Promise<void> {
    return this.run(store, 'readwrite', (tx) => {
      if (key !== undefined) tx.objectStore(store).put(value, key); else tx.objectStore(store).put(value);
      return () => undefined;
    });
  }

  putAll(store: StoreName, values: unknown[]): Promise<void> {
    if (values.length === 0) return Promise.resolve();
    return this.run(store, 'readwrite', (tx) => {
      const os = tx.objectStore(store);
      for (const v of values) os.put(v);
      return () => undefined;
    });
  }

  delete(store: StoreName, key: IDBValidKey | IDBKeyRange): Promise<void> {
    return this.run(store, 'readwrite', (tx) => {
      tx.objectStore(store).delete(key);
      return () => undefined;
    });
  }

  close(): void {
    this.idb.close();
  }
}

/** Accumulates records and writes them in batched transactions (bounded memory during imports). */
export class BatchWriter {
  private readonly db: Db;
  private readonly batchSize: number;
  private pending: { [store: string]: unknown[] } = {};
  private count = 0;

  constructor(db: Db, batchSize = 2000) {
    this.db = db;
    this.batchSize = batchSize;
  }

  add(store: StoreName, value: unknown): void {
    (this.pending[store] || (this.pending[store] = [])).push(value);
    this.count++;
  }

  get full(): boolean {
    return this.count >= this.batchSize;
  }

  async flushIfFull(): Promise<void> {
    if (this.full) await this.flush();
  }

  flush(): Promise<void> {
    if (this.count === 0) return Promise.resolve();
    const batch = this.pending;
    this.pending = {};
    this.count = 0;
    const stores = Object.keys(batch) as StoreName[];
    return this.db.run(stores, 'readwrite', (tx) => {
      for (const s of stores) {
        const os = tx.objectStore(s);
        for (const v of batch[s]) os.put(v);
      }
      return () => undefined;
    });
  }
}

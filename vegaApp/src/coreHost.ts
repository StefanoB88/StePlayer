// Creates the shared core (../tizenApp/src/core) for Vega: same lists, guide, search and sync logic as the Samsung/LG
// TV app. Storage: fake-indexeddb (pure JS IndexedDB) saved to /data by platform/persist.ts.
import './platform/polyfills';
import 'fake-indexeddb/auto';
import { createCore, type Core } from '../../tizenApp/src/core/index.ts';
import { persistIndexedDb, setPersistLogger } from './platform/persist';
import { devLog } from './devlog';

setPersistLogger((m) => devLog(`persist: ${m}`));

let core: Promise<Core> | null = null;

export function getCore(): Promise<Core> {
  if (!core) {
    core = (async () => {
      const c = await createCore({ deviceLanguages: ['it', 'en'], userAgent: 'StePlayer/1.0 (Vega)' });
      await persistIndexedDb('steplayer', 1); // same name/version as the core's db.ts
      return c;
    })();
  }
  return core;
}

// The shared core and the active list (source), for every screen.
import React, { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { Core, Source } from '../../../tizenApp/src/core/index.ts';

interface Session { core: Core; source: Source | null; sources: Source[]; reload(): Promise<void> }

const Ctx = createContext<Session | null>(null);
export const useSession = (): Session => {
  const s = useContext(Ctx);
  if (!s) throw new Error('session outside provider');
  return s;
};

export function SessionProvider({ core, children }: { core: Core; children: React.ReactNode }) {
  const [source, setSource] = useState<Source | null>(null);
  const [sources, setSources] = useState<Source[]>([]);
  const reload = useCallback(async () => {
    const [cur, all] = await Promise.all([core.sources.current(), core.sources.all()]);
    setSource(cur);
    setSources(all);
  }, [core]);
  useEffect(() => { reload().catch(() => undefined); return core.subscribe('sources', () => { reload().catch(() => undefined); }); }, [core, reload]);
  return <Ctx.Provider value={{ core, source, sources, reload }}>{children}</Ctx.Provider>;
}

/** Re-runs `load` when the core reports a change of one of `events` (catalog synced, favourites...). */
export function useCoreData<T>(load: () => Promise<T>, deps: unknown[], events: Array<'sources' | 'catalog' | 'epg' | 'library' | 'settings'> = []): T | undefined {
  const { core } = useSession();
  const [data, setData] = useState<T>();
  useEffect(() => {
    let alive = true;
    const run = () => { load().then((d) => { if (alive) setData(d); }).catch(() => undefined); };
    run();
    const offs = events.map((e) => core.subscribe(e, run));
    return () => { alive = false; offs.forEach((o) => o()); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return data;
}

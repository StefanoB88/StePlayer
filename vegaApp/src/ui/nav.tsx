// Screen stack (push / pop / reset) and the remote's Back key. Back pops one screen; on the first screen it leaves
// the app, unless the screen handles it (closing a panel).
import React, { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import { BackHandler, View, StyleSheet } from 'react-native';

export interface Route { key: string; render: () => React.ReactElement }

interface Nav {
  push(r: Omit<Route, 'key'>): void;
  pop(): void;
  reset(r: Omit<Route, 'key'>): void;
  /** The top screen may intercept Back (return true when handled). */
  setBackHandler(fn: (() => boolean) | null): void;
  depth: number;
}

const Ctx = createContext<Nav | null>(null);
export const useNav = (): Nav => {
  const n = useContext(Ctx);
  if (!n) throw new Error('nav outside provider');
  return n;
};

const TopContext = createContext(true);
/** True while this screen is the visible one (refresh data / restart timers when it comes back). */
export const useIsTop = (): boolean => useContext(TopContext);

const styles = StyleSheet.create({ hidden: { display: 'none' } });

let seq = 0;

export function NavHost({ initial }: { initial: Omit<Route, 'key'> }) {
  const [stack, setStack] = useState<Route[]>([{ key: `r${seq++}`, ...initial }]);
  const backHandler = useRef<(() => boolean) | null>(null);
  const push = useCallback((r: Omit<Route, 'key'>) => { backHandler.current = null; setStack((s) => [...s, { key: `r${seq++}`, ...r }]); }, []);
  const pop = useCallback(() => { backHandler.current = null; setStack((s) => (s.length > 1 ? s.slice(0, -1) : s)); }, []);
  const reset = useCallback((r: Omit<Route, 'key'>) => { backHandler.current = null; setStack([{ key: `r${seq++}`, ...r }]); }, []);
  const setBackHandler = useCallback((fn: (() => boolean) | null) => { backHandler.current = fn; }, []);

  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (backHandler.current && backHandler.current()) return true;
      if (stack.length > 1) { pop(); return true; }
      return false; // first screen: the system leaves the app
    });
    return () => sub.remove();
  }, [stack.length, pop]);

  const nav: Nav = { push, pop, reset, setBackHandler, depth: stack.length };
  return (
    <Ctx.Provider value={nav}>
      {/* Covered screens stay mounted (scroll and focus kept for Back) but hidden: not drawn, not focusable. */}
      {stack.map((r, i) => (
        <TopContext.Provider key={r.key} value={i === stack.length - 1}>
          <View style={[StyleSheet.absoluteFill, i === stack.length - 1 ? null : styles.hidden]}>{r.render()}</View>
        </TopContext.Provider>
      ))}
    </Ctx.Provider>
  );
}

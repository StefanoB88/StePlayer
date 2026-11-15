// Samsung TV system integration required by the Seller Office QA checklist: screen saver off while a video plays,
// clear message when the network goes away. Every call is guarded (no webapis in a desktop browser).
import { h } from './dom.ts';

interface AppCommon {
  setScreenSaver(state: number, ok?: () => void, err?: (e: unknown) => void): void;
  AppCommonScreenSaverState: { SCREEN_SAVER_OFF: number; SCREEN_SAVER_ON: number };
}
interface NetworkApi {
  isConnectedToGateway?(): boolean;
  addNetworkStateChangeListener?(cb: (state: number) => void): number;
  NetworkState?: { GATEWAY_DISCONNECTED?: number; LAN_CABLE_DETACHED?: number; GATEWAY_CONNECTED?: number; LAN_CABLE_ATTACHED?: number };
}

function webapis(): { appcommon?: AppCommon; network?: NetworkApi } | null {
  return (window as unknown as { webapis?: { appcommon?: AppCommon; network?: NetworkApi } }).webapis || null;
}

let screenSaverOff: boolean | null = null;

/** Off while a video is actually playing, on otherwise (paused, menus, player closed). */
export function setScreenSaverAllowed(allowed: boolean): void {
  const off = !allowed;
  if (screenSaverOff === off) return;
  screenSaverOff = off;
  try {
    const ac = webapis() && webapis()!.appcommon;
    if (!ac) return;
    ac.setScreenSaver(off ? ac.AppCommonScreenSaverState.SCREEN_SAVER_OFF : ac.AppCommonScreenSaverState.SCREEN_SAVER_ON);
  } catch { /* older firmware */ }
}

/** Persistent banner while the TV is offline (browser events + Samsung network listener). */
export function watchNetwork(): void {
  const banner = h('div', { class: 'net-banner hidden' }, 'Connessione assente: controlla la rete del televisore. StePlayer riprende da solo quando torna.');
  document.body.appendChild(banner);
  let offline = false;
  const set = (isOffline: boolean) => {
    if (offline === isOffline) return;
    offline = isOffline;
    banner.classList.toggle('hidden', !isOffline);
  };
  window.addEventListener('offline', () => set(true));
  window.addEventListener('online', () => set(false));
  try {
    const net = webapis() && webapis()!.network;
    if (net && net.addNetworkStateChangeListener) {
      const S = net.NetworkState || {};
      net.addNetworkStateChangeListener((state) => {
        if (state === S.GATEWAY_DISCONNECTED || state === S.LAN_CABLE_DETACHED) set(true);
        else if (state === S.GATEWAY_CONNECTED || state === S.LAN_CABLE_ATTACHED) set(false);
      });
    }
    if (net && net.isConnectedToGateway && !net.isConnectedToGateway()) set(true);
  } catch { /* desktop */ }
  if (navigator.onLine === false) set(true);
}

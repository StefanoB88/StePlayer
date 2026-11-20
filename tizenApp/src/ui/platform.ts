// Which TV the app runs on. One bundle for Samsung (Tizen) and LG (webOS); a desktop browser is used for development.
// Everything platform-specific (keys, exit, local IP, player engine) asks here.

export type Platform = 'tizen' | 'webos' | 'browser';

/* eslint-disable @typescript-eslint/no-explicit-any */
const w = window as any;

export const platform: Platform = w.tizen ? 'tizen'
  : (w.PalmSystem || w.PalmServiceBridge || /Web0S|webOS/i.test(navigator.userAgent)) ? 'webos'
  : 'browser';

export const isWebOS = platform === 'webos';

let localIp: string | null = null;
/** Bridges kept alive until their answer arrives (a bridge is garbage-collected otherwise). */
const pendingBridges: unknown[] = [];

/** webOS: asks the connection manager (public Luna service, ACG "network.query" in appinfo.json) for the TV's address, for the phone link. */
function requestWebOSIp(): void {
  const Bridge = w.PalmServiceBridge;
  if (!Bridge) return;
  try {
    const bridge = new Bridge();
    pendingBridges.push(bridge);
    bridge.onservicecallback = (msg: string) => {
      pendingBridges.splice(pendingBridges.indexOf(bridge), 1);
      try {
        const r = JSON.parse(msg);
        const pick = (n: any) => (n && n.state === 'connected' && n.ipAddress) || null;
        localIp = pick(r.wired) || pick(r.wifi) || localIp;
      } catch { /* malformed answer */ }
    };
    bridge.call('luna://com.palm.connectionmanager/getStatus', '{}');
  } catch { /* no Luna access */ }
}

/** Called once at start-up. */
export function initPlatform(): void {
  if (isWebOS) requestWebOSIp();
}

/** The TV's LAN address, or null when unknown. */
export function platformLocalIp(): string | null {
  if (platform === 'tizen') {
    try {
      const ip = w.webapis && w.webapis.network ? w.webapis.network.getIp() : null;
      if (ip) return ip;
    } catch { /* no permission */ }
    return null;
  }
  if (isWebOS && !localIp) requestWebOSIp(); // e.g. the network came up after the start
  return localIp;
}

/** Leaves the app the way each TV expects: Samsung closes it, LG goes back to the launcher (Recent list). */
export function exitApp(): void {
  if (platform === 'tizen') {
    try { w.tizen.application.getCurrentApplication().exit(); } catch { /* old firmware */ }
    return;
  }
  if (isWebOS) {
    try {
      if (w.PalmSystem && typeof w.PalmSystem.platformBack === 'function') { w.PalmSystem.platformBack(); return; }
    } catch { /* fall back to closing */ }
    try { window.close(); } catch { /* ignore */ }
  }
}

// "Usa il telefono" on Samsung TVs: Tizen web apps can't open a server, so the phone does (StePlayer app,
// "Invia una lista alla TV", Kotlin SendToTvServer). The TV scans its /24 network for that endpoint and asks for
// the lists with the 4-digit code shown on the phone. Everything stays on the local network.
import type { SourceInput } from '../api.ts';

export const PHONE_PORTS = [47620, 47621, 47622];
const HELLO = '/steplayer/v1/hello';
const CLAIM = '/steplayer/v1/claim';

export interface Phone { host: string; port: number; device: string }

/** Minimal HTTP for the link (XMLHttpRequest on the TV: timeouts without AbortController). */
export interface LinkHttp {
  request(method: 'GET' | 'POST', url: string, body: string | null, timeoutMs: number): Promise<{ status: number; text: string }>;
}

export function xhrHttp(): LinkHttp {
  return {
    request(method, url, body, timeoutMs) {
      return new Promise((resolve) => {
        const x = new XMLHttpRequest();
        let done = false;
        const finish = (status: number, text: string) => { if (!done) { done = true; resolve({ status, text }); } };
        x.open(method, url, true);
        x.timeout = timeoutMs;
        x.onload = () => finish(x.status, x.responseText || '');
        x.onerror = () => finish(0, '');
        x.ontimeout = () => finish(0, '');
        // text/plain: a "simple" request, no CORS preflight.
        if (body != null) x.setRequestHeader('Content-Type', 'text/plain');
        x.send(body);
      });
    },
  };
}

/** "192.168.1.23" → "192.168.1"; null for anything that isn't a private IPv4. */
export function subnetOf(ip: string | null | undefined): string | null {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec((ip || '').trim());
  if (!m) return null;
  const a = Number(m[1]);
  const b = Number(m[2]);
  const isPrivate = a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
  return isPrivate ? `${m[1]}.${m[2]}.${m[3]}` : null;
}

/** "192.168.1.23:47620", "192.168.1.23" → host/port (default port); null if not a LAN address. */
export function parseAddress(text: string): { host: string; port: number } | null {
  const m = /^\s*(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{2,5}))?\s*$/.exec(text);
  if (!m || !subnetOf(m[1])) return null;
  return { host: m[1], port: m[2] ? Number(m[2]) : PHONE_PORTS[0] };
}

async function hello(http: LinkHttp, host: string, port: number, timeoutMs: number): Promise<Phone | null> {
  const r = await http.request('GET', `http://${host}:${port}${HELLO}`, null, timeoutMs);
  if (r.status !== 200) return null;
  try {
    const j = JSON.parse(r.text) as { app?: string; device?: string };
    return j.app === 'StePlayer' ? { host, port, device: String(j.device || 'Telefono') } : null;
  } catch { return null; }
}

/**
 * Scans `subnet`.1–254 on the phone ports (most likely hosts first), `concurrency` requests at a time.
 * Calls `onFound` as phones answer; resolves with all of them. `isCancelled` stops early.
 */
export async function scanForPhones(http: LinkHttp, subnet: string, opts: {
  prefer?: string[]; timeoutMs?: number; concurrency?: number; onFound?: (p: Phone) => void; isCancelled?: () => boolean;
} = {}): Promise<Phone[]> {
  const timeout = opts.timeoutMs ?? 1200;
  const hosts: string[] = [];
  for (const h of opts.prefer || []) if (h.indexOf(`${subnet}.`) === 0 && hosts.indexOf(h) < 0) hosts.push(h);
  for (let i = 1; i <= 254; i++) { const h = `${subnet}.${i}`; if (hosts.indexOf(h) < 0) hosts.push(h); }
  const targets: { host: string; port: number }[] = [];
  for (const port of PHONE_PORTS.slice(0, 1)) for (const host of hosts) targets.push({ host, port });
  const found: Phone[] = [];
  let next = 0;
  const worker = async () => {
    while (next < targets.length && !(opts.isCancelled && opts.isCancelled())) {
      const t = targets[next++];
      const p = await hello(http, t.host, t.port, timeout);
      if (p) { found.push(p); if (opts.onFound) opts.onFound(p); }
    }
  };
  const n = Math.max(1, opts.concurrency ?? 32);
  const workers: Promise<void>[] = [];
  for (let i = 0; i < n; i++) workers.push(worker());
  await Promise.all(workers);
  return found;
}

/** Checks one known address (manual entry / last phone): tries the alternative ports too. */
export async function probePhone(http: LinkHttp, host: string, port?: number): Promise<Phone | null> {
  const ports = port ? [port] : PHONE_PORTS;
  for (const p of ports) {
    const ph = await hello(http, host, p, 2500);
    if (ph) return ph;
  }
  return null;
}

export type ClaimResult =
  | { ok: true; lists: SourceInput[] }
  | { ok: false; reason: 'wrong-code' | 'gone' | 'unreachable' | 'empty' };

interface SentList {
  kind: string; name?: string; serverUrl?: string; username?: string; password?: string;
  listUrl?: string; epgUrl?: string; userAgent?: string;
}

export function toInputs(lists: SentList[]): SourceInput[] {
  const out: SourceInput[] = [];
  for (const l of lists) {
    if (l.kind === 'xtream' && l.serverUrl && l.username && l.password) {
      out.push({ kind: 'xtream', name: l.name || '', serverUrl: l.serverUrl, username: l.username, password: l.password, userAgent: l.userAgent || null });
    } else if (l.kind === 'm3u' && l.listUrl) {
      out.push({ kind: 'm3u', name: l.name || '', listUrl: l.listUrl, epgUrl: l.epgUrl || null, userAgent: l.userAgent || null });
    }
  }
  return out;
}

export async function claimLists(http: LinkHttp, phone: { host: string; port: number }, code: string): Promise<ClaimResult> {
  const r = await http.request('POST', `http://${phone.host}:${phone.port}${CLAIM}`, JSON.stringify({ code }), 6000);
  if (r.status === 403) return { ok: false, reason: 'wrong-code' };
  if (r.status === 410) return { ok: false, reason: 'gone' };
  if (r.status !== 200) return { ok: false, reason: r.status === 404 ? 'empty' : 'unreachable' };
  try {
    const lists = toInputs((JSON.parse(r.text) as { lists?: SentList[] }).lists || []);
    return lists.length ? { ok: true, lists } : { ok: false, reason: 'empty' };
  } catch { return { ok: false, reason: 'unreachable' }; }
}

// Port of net/NetworkErrors.kt: turns any failure of a request (provider, M3U list, TV guide) into an
// XtreamException whose message says exactly what is wrong and what to do.
//
// A web page sees much less than a native app: a failed fetch is an opaque TypeError (wrong address,
// server down, closed port, invalid certificate and blocked mixed content all look the same). What can
// be told apart: the device is offline (navigator.onLine), our own timeouts, a drop while reading the
// body, and every HTTP status.
import { XtreamException, type XtreamErrorKind } from '../xtream/xtreamException.ts';
import { HttpError } from './http.ts';

/** Who we were talking to, for the wording. */
export type NetworkTarget = 'SERVER' | 'LIST' | 'GUIDE';

/** What the device knows about its own connection (no extra requests). */
export type NetworkStatus = 'ONLINE' | 'OFFLINE' | 'UNKNOWN';

function browserStatus(): NetworkStatus {
  const nav = (globalThis as { navigator?: { onLine?: boolean } }).navigator;
  if (!nav || typeof nav.onLine !== 'boolean') return 'UNKNOWN';
  // onLine === true only means "some network interface is up": it never proves Internet works.
  return nav.onLine ? 'UNKNOWN' : 'OFFLINE';
}

function where(target: NetworkTarget): string {
  switch (target) {
    case 'SERVER': return 'Il server del provider';
    case 'LIST': return 'Il server della lista';
    default: return 'Il server della guida TV';
  }
}

function lowerFirst(s: string): string {
  return s.charAt(0).toLowerCase() + s.slice(1);
}

function fallbackHost(url: string): string {
  const i = url.indexOf('://');
  const rest = i >= 0 ? url.slice(i + 3) : url;
  const authority = rest.split('/')[0].split('?')[0];
  return authority.slice(authority.lastIndexOf('@') + 1); // never user:password@
}

export const NetworkErrors = {
  /** Replaceable (tests, platform integrations). */
  status: browserStatus as () => NetworkStatus,

  /**
   * True if e is a failure of the connection itself (as opposed to e.g. a storage error that happened
   * while a download was being processed).
   */
  isNetworkFailure(e: unknown): boolean {
    if (e instanceof HttpError) return true;
    // Another Http implementation (raw fetch): the browser's opaque failure.
    return e instanceof TypeError && /fetch|network|load failed/i.test(e.message || '');
  },

  /** A failure before/while receiving the response (no HTTP status). */
  fromThrowable(e: unknown, url: string, target: NetworkTarget): XtreamException {
    if (e instanceof XtreamException) return e;
    let status: NetworkStatus = 'UNKNOWN';
    try { status = NetworkErrors.status(); } catch { /* unknown */ }
    const w = where(target);
    const host = NetworkErrors.hostOf(url);
    const err = (kind: XtreamErrorKind, message: string) => new XtreamException(message, kind);

    if (status === 'OFFLINE') {
      return err('NO_INTERNET', 'Il dispositivo non è connesso a Internet. Controlla il Wi-Fi o la rete dati e riprova.');
    }
    const failure = e instanceof HttpError ? e.failure : null;
    if (failure === 'timeout') {
      return err('TIMEOUT', `${w} non risponde (tempo scaduto): potrebbe essere sovraccarico o offline. Riprova tra qualche minuto.`);
    }
    if (failure === 'interrupted') {
      return err('NETWORK', `La connessione con ${lowerFirst(w)} si è interrotta. Riprova.`);
    }
    return err('UNREACHABLE',
      `Impossibile contattare ${lowerFirst(w)} («${host}»): l'indirizzo potrebbe essere sbagliato, il server spento o la connessione assente.`);
  },

  /** The server answered with a non-2xx status. */
  fromStatus(code: number, target: NetworkTarget): XtreamException {
    const err = (kind: XtreamErrorKind, message: string) => new XtreamException(message, kind, code);
    const w = where(target);
    if (code === 401 || code === 403) {
      if (target === 'SERVER') return err('INVALID_CREDENTIALS', 'Username o password non validi.');
      return err('INVALID_CREDENTIALS',
        `Accesso negato (errore ${code}): il link della ${target === 'LIST' ? 'lista' : 'guida TV'} non è più valido o l'abbonamento è scaduto.`);
    }
    if (code === 404 || code === 410) {
      if (target === 'SERVER') return err('NOT_FOUND', `Il server risponde, ma l'indirizzo non è quello giusto (errore ${code}). Controlla indirizzo e porta.`);
      if (target === 'LIST') return err('NOT_FOUND', `La lista non si trova più a questo indirizzo (errore ${code}): il link è sbagliato o scaduto.`);
      return err('NOT_FOUND', `La guida TV non si trova più a questo indirizzo (errore ${code}).`);
    }
    if (code === 429) {
      return err('TOO_MANY_REQUESTS', `${w} rifiuta le richieste perché sono troppe (errore 429). Aspetta qualche minuto e riprova.`);
    }
    if (code === 502 || code === 503 || code === 504) {
      return err('SERVER', `${w} è temporaneamente fuori servizio (errore ${code}). Non dipende dall'app né dalla tua rete: riprova più tardi.`);
    }
    if (code >= 500 && code <= 599) {
      return err('SERVER', `${w} ha un problema interno (errore ${code}). Non dipende dall'app né dalla tua rete: riprova più tardi.`);
    }
    return err('SERVER', `${w} ha risposto con un errore inatteso (codice ${code}). Riprova più tardi.`);
  },

  /** "host" or "host:port" when the port isn't the scheme's default. Never the path/credentials. */
  hostOf(url: string): string {
    try {
      const u = new URL(url);
      if (u.host) return u.host; // URL.host already leaves the default port out
    } catch { /* not a full URL */ }
    return fallbackHost(url);
  },
};

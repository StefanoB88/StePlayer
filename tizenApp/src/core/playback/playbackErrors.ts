// Port of playback/PlaybackErrors.kt (+ RetryPolicy, StallWatchdog) and BufferAdvisor.kt.
import type {
  BufferAdvisor as BufferAdvisorApi, BufferMode, BufferProfile, PlaybackError, RetryPolicy as RetryPolicyApi,
  StallWatchdog as StallWatchdogApi,
} from '../api.ts';

/** Maps raw player/HTTP failures to PlaybackError. */
export const PlaybackErrors = {
  fromHttpStatus(code: number, maxConnections: number | null = null): PlaybackError {
    // API Xtream servers answer 403/458/509 (some 406/429/503) when the connection limit is reached.
    if (code === 403 || code === 406 || code === 429 || code === 458 || code === 509) return PlaybackErrors.maxConnections(maxConnections);
    if (code === 401) {
      return {
        kind: 'UNAUTHORIZED', title: 'Accesso negato',
        message: 'Il server non accetta più le credenziali. Controlla username e password nelle impostazioni.', retryable: false,
      };
    }
    if (code === 404 || code === 410) {
      return {
        kind: 'NOT_FOUND', title: 'Contenuto non disponibile',
        message: 'Il provider non trova questo contenuto. Prova ad aggiornare la lista.', retryable: false,
      };
    }
    if (code >= 500 && code <= 599) {
      return {
        kind: 'SERVER', title: 'Problema del provider',
        message: `Il server del provider non risponde correttamente (${code}).`, retryable: true,
      };
    }
    return PlaybackErrors.unknown(`HTTP ${code}`);
  },

  maxConnections(max: number | null): PlaybackError {
    let message = 'Il provider ha rifiutato la connessione: ';
    message += max != null
      ? `il tuo abbonamento consente ${max} ${max === 1 ? 'connessione' : 'connessioni'} `
      : 'hai raggiunto il limite di connessioni ';
    message += 'e risulta già in uso (su questo o un altro dispositivo). ';
    message += 'Il server può impiegare qualche secondo a liberarla: riprovo automaticamente.';
    return { kind: 'MAX_CONNECTIONS', title: 'Troppe connessioni', message, retryable: true };
  },

  network(): PlaybackError {
    return {
      kind: 'NETWORK', title: 'Connessione assente',
      message: 'Controlla la connessione a Internet. Riprovo appena torna disponibile.', retryable: true,
    };
  },

  timeout(): PlaybackError {
    return { kind: 'TIMEOUT', title: 'Il server non risponde', message: 'Il provider è lento a rispondere. Riprovo automaticamente.', retryable: true };
  },

  stalled(): PlaybackError {
    return { kind: 'STALLED', title: 'Riproduzione bloccata', message: 'Lo stream si è fermato. Riconnessione in corso…', retryable: true };
  },

  unsupported(container: string | null): PlaybackError {
    return {
      kind: 'UNSUPPORTED_FORMAT', title: 'Formato non supportato',
      message: `Questo dispositivo non riesce a riprodurre il formato${container != null ? ` .${container}` : ''}.`, retryable: false,
    };
  },

  expired(): PlaybackError {
    return {
      kind: 'ACCOUNT_EXPIRED', title: 'Abbonamento scaduto',
      message: 'Il tuo abbonamento risulta scaduto o disattivato dal provider.', retryable: false,
    };
  },

  unknown(detail: string | null = null): PlaybackError {
    return {
      kind: 'UNKNOWN', title: 'Impossibile riprodurre',
      message: `Si è verificato un errore${detail != null ? ` (${detail})` : ''}. Riprova tra poco.`, retryable: true,
    };
  },
};

/**
 * Backoff for automatic reconnection. One instance per playback attempt; call reset() once the
 * stream plays fine for a while. Returns null when it's time to stop and show the error UI.
 */
export class RetryPolicy implements RetryPolicyApi {
  private readonly isLive: boolean;
  private attempt = 0;

  constructor(isLive: boolean) {
    this.isLive = isLive;
  }

  nextDelayMs(error: PlaybackError): number | null {
    if (!error.retryable) return null;
    let schedule: number[];
    if (error.kind === 'MAX_CONNECTIONS') schedule = [4_000, 10_000, 20_000]; // provider needs time to free the slot
    else if (error.kind === 'NETWORK') schedule = [2_000, 4_000, 8_000, 15_000, 30_000];
    else schedule = this.isLive ? [1_000, 2_000, 4_000, 8_000, 15_000] : [1_500, 4_000];
    const v = schedule[this.attempt++];
    return v === undefined ? null : v;
  }

  get attempts(): number {
    return this.attempt;
  }

  reset(): void {
    this.attempt = 0;
  }
}

/**
 * Detects a frozen stream: buffering (or position not advancing while "playing") for too long.
 * Feed it from a 1-second ticker; when tick() returns true, treat it as PlaybackErrors.stalled().
 */
export class StallWatchdog implements StallWatchdogApi {
  private readonly stallTimeoutMs: number;
  private lastPosition = -1;
  private lastAdvanceAt = -1;

  constructor(isLive: boolean, stallTimeoutMs: number = isLive ? 12_000 : 25_000) {
    this.stallTimeoutMs = stallTimeoutMs;
  }

  tick(nowMs: number, positionMs: number, wantsToPlay: boolean): boolean {
    if (!wantsToPlay) { this.lastAdvanceAt = nowMs; this.lastPosition = positionMs; return false; }
    if (this.lastAdvanceAt < 0 || positionMs !== this.lastPosition) {
      this.lastPosition = positionMs;
      this.lastAdvanceAt = nowMs;
      return false;
    }
    return nowMs - this.lastAdvanceAt >= this.stallTimeoutMs;
  }

  reset(): void {
    this.lastPosition = -1;
    this.lastAdvanceAt = -1;
  }
}

const WINDOW_MS = 10 * 60_000;
const STALLS_TO_ESCALATE = 2;

export const BUFFER_NORMAL: BufferProfile = {
  name: 'Normale', minBufferMs: 15_000, maxBufferMs: 50_000, bufferForPlaybackMs: 1_500,
  bufferForPlaybackAfterRebufferMs: 3_000, liveTargetOffsetMs: 8_000,
};
/** Big reserve once playing, but starts as fast as NORMAL-ish (2 s / 5 s after a rebuffer). */
export const BUFFER_LARGE: BufferProfile = {
  name: 'Grande', minBufferMs: 30_000, maxBufferMs: 120_000, bufferForPlaybackMs: 2_000,
  bufferForPlaybackAfterRebufferMs: 5_000, liveTargetOffsetMs: 20_000,
};

/**
 * Picks the buffer profile. In AUTO mode it starts small (fast zapping) and switches to the large
 * profile after repeated stalls, until the app restarts. Read profile() at every stream start.
 */
export class BufferAdvisor implements BufferAdvisorApi {
  private readonly mode: BufferMode;
  private stalls: number[] = [];
  private escalated = false;

  constructor(mode: BufferMode) {
    this.mode = mode;
  }

  profile(): BufferProfile {
    return this.mode === 'LARGE' || (this.mode === 'AUTO' && this.escalated) ? { ...BUFFER_LARGE } : { ...BUFFER_NORMAL };
  }

  /** Report every stall / rebuffer > 1 s. Returns true if the profile just changed (restart the stream to apply). */
  onStall(nowMs: number): boolean {
    if (this.mode !== 'AUTO' || this.escalated) return false;
    this.stalls.push(nowMs);
    this.stalls = this.stalls.filter((t) => !(nowMs - t > WINDOW_MS));
    if (this.stalls.length >= STALLS_TO_ESCALATE) {
      this.escalated = true;
      return true;
    }
    return false;
  }

  get isEscalated(): boolean {
    return this.escalated;
  }
}

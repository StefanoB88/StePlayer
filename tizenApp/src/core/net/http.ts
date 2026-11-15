// Injectable HTTP abstraction (Kotlin used Ktor). Streaming first: big lists and guides are read
// chunk by chunk, never buffered whole.
//
// Browsers forbid the User-Agent header on fetch/XHR, so catalog requests never send one; the
// configured user agent travels in PlayableStream.headers for the player instead.

export interface HttpResponse {
  readonly status: number;
  /** Next body chunk, or null at the end. Rejects on network errors/timeouts. */
  read(): Promise<Uint8Array | null>;
  /** Stops the download and frees the connection. Safe to call more than once. */
  cancel(): void;
}

export interface Http {
  get(url: string): Promise<HttpResponse>;
}

/** Network failure with an Italian message (never contains the URL: it may carry credentials). */
export class HttpError extends Error {
  readonly timeout: boolean;
  constructor(message: string, timeout = false) {
    super(message);
    this.name = 'HttpError';
    this.timeout = timeout;
  }
}

export function isSuccess(status: number): boolean {
  return status >= 200 && status <= 299;
}

/** Reads the whole body (only for small responses). */
export async function readAll(res: HttpResponse): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const chunk = await res.read();
    if (chunk == null) break;
    parts.push(chunk);
    total += chunk.length;
  }
  if (parts.length === 1) return parts[0];
  const out = new Uint8Array(total);
  let off = 0;
  for (const p of parts) { out.set(p, off); off += p.length; }
  return out;
}

export async function readText(res: HttpResponse): Promise<string> {
  return new TextDecoder('utf-8').decode(await readAll(res));
}

export interface FetchHttpOptions {
  /** Time to receive the response headers (Kotlin: connect timeout 15 s). */
  connectTimeoutMs?: number;
  /** Max silence while reading the body (Kotlin: socket timeout 60 s). */
  readTimeoutMs?: number;
  /** Retries on network errors and 5xx except 501 (Kotlin HttpRequestRetry: 2). */
  maxRetries?: number;
  retryBaseDelayMs?: number;
}

function withTimeout<T>(p: Promise<T>, ms: number, onTimeout: () => void): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => {
      onTimeout();
      reject(new HttpError('Il server non risponde (tempo scaduto)', true));
    }, ms);
    p.then((v) => { clearTimeout(timer); resolve(v); }, (e) => { clearTimeout(timer); reject(e); });
  });
}

function delay(ms: number): Promise<void> {
  return new Promise((r) => setTimeout(r, ms));
}

interface MinimalReader {
  read(): Promise<{ done: boolean; value?: Uint8Array }>;
  cancel(): Promise<void>;
}

/** fetch() + ReadableStream reader, available on Chromium 56 (Tizen 4.0). No AbortController needed. */
export function createFetchHttp(options: FetchHttpOptions = {}): Http {
  const connectTimeout = options.connectTimeoutMs ?? 15_000;
  const readTimeout = options.readTimeoutMs ?? 60_000;
  const maxRetries = options.maxRetries ?? 2;
  const baseDelay = options.retryBaseDelayMs ?? 1_000;

  async function once(url: string): Promise<HttpResponse> {
    const Ctrl = (globalThis as { AbortController?: { new(): { abort(): void; signal: unknown } } }).AbortController;
    const ctrl = Ctrl ? new Ctrl() : null;
    const init: { [k: string]: unknown } = { method: 'GET', credentials: 'omit', redirect: 'follow' };
    if (ctrl) init.signal = ctrl.signal;
    let response: Response;
    try {
      response = await withTimeout(fetch(url, init as RequestInit), connectTimeout, () => { if (ctrl) ctrl.abort(); });
    } catch (e) {
      if (e instanceof HttpError) throw e;
      throw new HttpError('Connessione al server non riuscita');
    }
    const body = response.body as unknown as { getReader(): MinimalReader } | null;
    let reader: MinimalReader | null = body ? body.getReader() : null;
    let buffered: Uint8Array | null = null;
    let done = false;
    if (!reader) {
      // Very old engines without streaming bodies: whole response at once.
      buffered = new Uint8Array(await response.arrayBuffer());
    }
    const cancel = (): void => {
      if (done) return;
      done = true;
      if (reader) reader.cancel().catch(() => undefined);
      if (ctrl) ctrl.abort();
    };
    return {
      status: response.status,
      async read(): Promise<Uint8Array | null> {
        if (done) return null;
        if (!reader) {
          const b = buffered;
          buffered = null;
          if (b == null) done = true;
          return b;
        }
        let r: { done: boolean; value?: Uint8Array };
        try {
          r = await withTimeout(reader.read(), readTimeout, cancel);
        } catch (e) {
          cancel();
          if (e instanceof HttpError) throw e;
          throw new HttpError('Connessione interrotta durante il download');
        }
        if (r.done || !r.value) { done = true; reader = null; return null; }
        return r.value;
      },
      cancel,
    };
  }

  return {
    async get(url: string): Promise<HttpResponse> {
      let attempt = 0;
      for (;;) {
        try {
          const res = await once(url);
          if (attempt < maxRetries && res.status >= 500 && res.status <= 599 && res.status !== 501) {
            res.cancel();
          } else {
            return res;
          }
        } catch (e) {
          if (attempt >= maxRetries) throw e;
        }
        attempt++;
        await delay(baseDelay * Math.pow(2, attempt) + Math.random() * 1000);
      }
    },
  };
}

// Port of epg/ByteStreams.kt (TextChunkDecoder): turns a stream of byte chunks (possibly gzip
// compressed) into text chunks. Gzip is detected by magic number, so it works for "xmltv.php" and
// "guide.xml.gz" alike. Decompression: native DecompressionStream when the engine has it
// (Chromium 80+), else pako (Chromium 56 = Tizen 4.0).
import { Inflate } from 'pako';

export interface ChunkReader {
  read(): Promise<Uint8Array | null>;
  cancel(): void;
}

export interface TextStreamOptions {
  /** Forces the pako fallback even where DecompressionStream exists (tests). */
  disableNativeGzip?: boolean;
}

type TextSink = (text: string) => void | Promise<void>;

interface NativeDecompressionStream {
  readable: { getReader(): { read(): Promise<{ done: boolean; value?: Uint8Array }>; cancel(): Promise<void> } };
  writable: unknown;
}

function nativeDecompression(): (new (format: string) => NativeDecompressionStream) | null {
  const g = globalThis as { DecompressionStream?: new (format: string) => NativeDecompressionStream; ReadableStream?: unknown };
  return typeof g.DecompressionStream === 'function' && typeof g.ReadableStream === 'function' ? g.DecompressionStream : null;
}

export function isGzip(bytes: Uint8Array): boolean {
  return bytes.length >= 2 && bytes[0] === 0x1f && bytes[1] === 0x8b;
}

/**
 * Reads [reader] to the end, calling [onText] with decoded UTF-8 text chunks (multi-byte sequences
 * split across chunks are handled). Awaits onText, so a slow consumer slows the download down
 * instead of piling text up in memory.
 */
export async function readTextStream(reader: ChunkReader, onText: TextSink, options: TextStreamOptions = {}): Promise<void> {
  // Detect: need the first two bytes.
  let head: Uint8Array | null = null;
  for (;;) {
    const chunk = await reader.read();
    if (chunk == null) break;
    if (chunk.length === 0) continue;
    head = head == null ? chunk : concat(head, chunk);
    if (head.length >= 2) break;
  }
  if (head == null) return;
  if (!isGzip(head)) {
    await plain(head, reader, onText);
    return;
  }
  const Native = options.disableNativeGzip ? null : nativeDecompression();
  if (Native) await gunzipNative(Native, head, reader, onText);
  else await gunzipPako(head, reader, onText);
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

async function plain(first: Uint8Array, reader: ChunkReader, onText: TextSink): Promise<void> {
  const utf8 = new TextDecoder('utf-8');
  let chunk: Uint8Array | null = first;
  while (chunk != null) {
    const text = utf8.decode(chunk, { stream: true });
    if (text.length > 0) await onText(text);
    chunk = await reader.read();
  }
  const rest = utf8.decode();
  if (rest.length > 0) await onText(rest);
}

async function gunzipPako(first: Uint8Array, reader: ChunkReader, onText: TextSink): Promise<void> {
  const utf8 = new TextDecoder('utf-8');
  const inflate = new Inflate({ chunkSize: 64 * 1024 });
  let out: string[] = [];
  let finished = false;
  let failure: string | null = null;
  inflate.onData = (data) => {
    const text = utf8.decode(data as Uint8Array, { stream: true });
    if (text.length > 0) out.push(text);
  };
  inflate.onEnd = (status) => {
    finished = true;
    if (status !== 0) {
      const strm = (inflate as unknown as { strm?: { msg?: string } }).strm;
      failure = strm && strm.msg ? strm.msg : `errore ${status}`;
    }
  };
  let chunk: Uint8Array | null = first;
  while (chunk != null) {
    inflate.push(chunk, false);
    if (failure != null) {
      reader.cancel();
      throw new Error(`Guida compressa non valida (${failure})`);
    }
    if (out.length > 0) {
      const texts = out;
      out = [];
      for (const t of texts) await onText(t);
    }
    if (finished) { reader.cancel(); break; } // end of the gzip member: like Kotlin, ignore the rest
    chunk = await reader.read();
  }
  const rest = utf8.decode();
  if (rest.length > 0) await onText(rest);
}

async function gunzipNative(
  Native: new (format: string) => NativeDecompressionStream,
  first: Uint8Array,
  reader: ChunkReader,
  onText: TextSink,
): Promise<void> {
  const RS = (globalThis as unknown as { ReadableStream: new (src: unknown) => { pipeThrough(t: unknown): NativeDecompressionStream['readable'] } }).ReadableStream;
  let pendingFirst: Uint8Array | null = first;
  const source = new RS({
    async pull(controller: { enqueue(c: Uint8Array): void; close(): void }) {
      if (pendingFirst) { controller.enqueue(pendingFirst); pendingFirst = null; return; }
      const chunk = await reader.read();
      if (chunk == null) controller.close(); else controller.enqueue(chunk);
    },
    cancel() { reader.cancel(); },
  });
  const out = source.pipeThrough(new Native('gzip')).getReader();
  const utf8 = new TextDecoder('utf-8');
  let produced = false;
  for (;;) {
    let r: { done: boolean; value?: Uint8Array };
    try {
      r = await out.read();
    } catch (e) {
      reader.cancel();
      // A truncated file keeps what was decoded so far (like the Kotlin inflater); garbage fails.
      if (produced) break;
      const msg = e instanceof Error ? e.message : String(e);
      throw new Error(`Guida compressa non valida (${msg})`);
    }
    if (r.done || !r.value) break;
    produced = true;
    const text = utf8.decode(r.value, { stream: true });
    if (text.length > 0) await onText(text);
  }
  const rest = utf8.decode();
  if (rest.length > 0) await onText(rest);
}

/** Splits a text stream into lines (LF or CRLF), for M3U lists. */
export class LineSplitter {
  private rest = '';
  private readonly onLine: (line: string) => void;

  constructor(onLine: (line: string) => void) {
    this.onLine = onLine;
  }

  feed(text: string): void {
    let data = this.rest.length > 0 ? this.rest + text : text;
    let start = 0;
    for (;;) {
      const nl = data.indexOf('\n', start);
      if (nl < 0) break;
      let end = nl;
      if (end > start && data.charCodeAt(end - 1) === 13) end--;
      this.onLine(data.substring(start, end));
      start = nl + 1;
    }
    this.rest = start < data.length ? data.substring(start) : '';
    data = '';
  }

  finish(): void {
    if (this.rest.length > 0) {
      const r = this.rest;
      this.rest = '';
      this.onLine(r.charCodeAt(r.length - 1) === 13 ? r.substring(0, r.length - 1) : r);
    }
  }
}

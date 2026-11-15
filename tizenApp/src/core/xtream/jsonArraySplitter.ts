// Port of xtream/JsonArraySplitter.kt.
//
// Incremental splitter for a top-level JSON array: feed it raw UTF-8 chunks as they arrive and it
// calls onElement with the bytes of each complete element, so callers can decode a huge array
// one item at a time. Works on bytes: JSON's structural characters are ASCII and never appear
// inside a UTF-8 multi-byte sequence, so chunk boundaries can fall anywhere.
//
// An object, `null` or an empty body (what some servers send for "no items") yields no elements.
// Anything else (e.g. an HTML error page) or a truncated array makes finish() throw, so a broken
// response can never be mistaken for an empty catalog. The bytes passed to onElement are reused:
// consume them before returning.

const LBRACKET = 0x5b, RBRACKET = 0x5d, LBRACE = 0x7b, RBRACE = 0x7d, LOWER_N = 0x6e, COMMA = 0x2c;
const QUOTE = 0x22, BACKSLASH = 0x5c, SPACE = 0x20, TAB = 0x09, LF = 0x0a, CR = 0x0d;
const BOM_1 = 0xef, BOM_2 = 0xbb, BOM_3 = 0xbf;

function isWhitespace(b: number): boolean {
  return b === SPACE || b === TAB || b === LF || b === CR;
}

export class JsonArraySplitterError extends Error {}

export class JsonArraySplitter {
  private readonly onElement: (bytes: Uint8Array, length: number) => void;
  private buf = new Uint8Array(4 * 1024);
  private len = 0;
  /** null = top-level value not seen yet. */
  private isArray: boolean | null = null;
  private firstByte = 0;
  private inElement = false;
  private depth = 0;
  private inString = false;
  private escape = false;
  /** True once the closing bracket (or a non-array body) was seen: the rest can be ignored. */
  isDone = false;

  constructor(onElement: (bytes: Uint8Array, length: number) => void) {
    this.onElement = onElement;
  }

  feed(bytes: Uint8Array, count: number = bytes.length): void {
    let i = 0;
    while (i < count && !this.isDone) {
      // Fast path inside strings: copy up to the next quote or backslash in one go.
      if (this.inString && !this.escape) {
        let j = i;
        while (j < count) {
          const b = bytes[j];
          if (b === QUOTE || b === BACKSLASH) break;
          j++;
        }
        if (j > i) {
          this.appendRange(bytes, i, j);
          i = j;
          continue;
        }
      }
      this.step(bytes[i]);
      i++;
    }
  }

  finish(): void {
    if (this.isArray === true) {
      if (!this.isDone) throw new JsonArraySplitterError('Truncated JSON array');
    } else if (this.isArray === false) {
      if (this.firstByte !== LBRACE && this.firstByte !== LOWER_N) throw new JsonArraySplitterError('Not a JSON array');
    }
  }

  private step(b: number): void {
    if (this.isArray == null) {
      if (isWhitespace(b) || b === BOM_1 || b === BOM_2 || b === BOM_3) return;
      this.firstByte = b;
      if (b === LBRACKET) {
        this.isArray = true;
      } else {
        this.isArray = false;
        this.isDone = true;
      }
      return;
    }
    if (!this.inElement) {
      if (isWhitespace(b) || b === COMMA) return;
      if (b === RBRACKET) { this.isDone = true; return; }
      this.inElement = true; this.len = 0; this.depth = 0;
    }
    if (this.inString) {
      this.append(b);
      if (this.escape) this.escape = false;
      else if (b === BACKSLASH) this.escape = true;
      else if (b === QUOTE) this.inString = false;
      return;
    }
    switch (b) {
      case QUOTE: this.inString = true; this.append(b); break;
      case LBRACE: case LBRACKET: this.depth++; this.append(b); break;
      case RBRACE: case RBRACKET:
        if (this.depth === 0 && b === RBRACKET) { // closes the outer array right after an element
          this.emit();
          this.isDone = true;
        } else {
          this.depth--;
          this.append(b);
        }
        break;
      case COMMA:
        if (this.depth === 0) this.emit(); else this.append(b);
        break;
      default:
        if (!(this.depth === 0 && isWhitespace(b))) this.append(b);
    }
  }

  private emit(): void {
    this.inElement = false;
    if (this.len > 0) this.onElement(this.buf, this.len);
    this.len = 0;
  }

  private ensure(extra: number): void {
    if (this.len + extra <= this.buf.length) return;
    let size = this.buf.length * 2;
    while (size < this.len + extra) size *= 2;
    const next = new Uint8Array(size);
    next.set(this.buf.subarray(0, this.len));
    this.buf = next;
  }

  private append(b: number): void {
    if (this.len === this.buf.length) this.ensure(1);
    this.buf[this.len++] = b;
  }

  private appendRange(bytes: Uint8Array, from: number, to: number): void {
    this.ensure(to - from);
    this.buf.set(bytes.subarray(from, to), this.len);
    this.len += to - from;
  }
}

// Port of epg/XmltvParser.kt: streaming, allocation-light XMLTV parser. Feed text chunks of any
// size via feed() and call finish() at the end. Tolerates truncated files, unknown tags, CDATA,
// comments and entities.
import { parseXmltv } from '../util/time.ts';

export interface XmltvChannel { id: string; displayName: string | null; icon: string | null }
export interface XmltvProgramme { channel: string; start: number; stop: number; title: string; description: string | null }

function isWs(c: number): boolean {
  // Kotlin Char.isWhitespace for the characters that matter in XML.
  return c === 32 || c === 9 || c === 10 || c === 13 || c === 12 || c === 11;
}

type Capture = 'title' | 'desc' | 'display-name' | null;

export class XmltvParser {
  private readonly onChannel: (c: XmltvChannel) => void;
  private readonly onProgramme: (p: XmltvProgramme) => void;
  /**
   * Optional cheap pre-filter on the raw `channel` attribute: returning false skips the
   * programme's text capture and entity decoding entirely (most of the parsing cost).
   */
  private readonly wantChannel: ((id: string) => boolean) | null;
  private buf = '';
  private pos = 0;

  private inProgramme = false;
  private skipProgramme = false;
  private pChannel: string | null = null;
  private pStart = 0;
  private pStop = 0;
  private pTitle: string | null = null;
  private pDesc: string | null = null;

  private inChannel = false;
  private cId: string | null = null;
  private cName: string | null = null;
  private cIcon: string | null = null;

  private capture: Capture = null;
  private text = '';

  constructor(
    onProgramme: (p: XmltvProgramme) => void,
    onChannel: (c: XmltvChannel) => void = () => undefined,
    wantChannel: ((id: string) => boolean) | null = null,
  ) {
    this.onProgramme = onProgramme;
    this.onChannel = onChannel;
    this.wantChannel = wantChannel;
  }

  feed(chunk: string): void {
    // Keep only the unprocessed tail (an incomplete tag) plus the new chunk.
    this.buf = this.pos < this.buf.length ? this.buf.substring(this.pos) + chunk : chunk;
    this.pos = 0;
    this.process();
  }

  finish(): void {
    this.process();
    this.buf = '';
    this.pos = 0;
  }

  private process(): void {
    const buf = this.buf;
    for (;;) {
      const lt = buf.indexOf('<', this.pos);
      if (lt < 0) {
        if (this.capture != null) this.text += buf.substring(this.pos);
        this.pos = buf.length;
        return;
      }
      if (this.capture != null && lt > this.pos) this.text += buf.substring(this.pos, lt);
      const next = buf.charCodeAt(lt + 1);
      if (next === 33 /* ! */) {
        // CDATA
        if (buf.startsWith('<![CDATA[', lt)) {
          const end = buf.indexOf(']]>', lt + 9);
          if (end < 0) { this.pos = lt; return; }
          if (this.capture != null) this.text += buf.substring(lt + 9, end);
          this.pos = end + 3;
          continue;
        }
        // Comment
        if (buf.startsWith('<!--', lt)) {
          const end = buf.indexOf('-->', lt + 4);
          if (end < 0) { this.pos = lt; return; }
          this.pos = end + 3;
          continue;
        }
        if (lt + 9 > buf.length) { this.pos = lt; return; } // could still become CDATA/comment
      } else if (next !== next /* NaN: '<' is the last char */) {
        this.pos = lt;
        return;
      }
      const gt = this.findTagEnd(lt + 1);
      if (gt < 0) { this.pos = lt; return; }
      this.handleTag(lt + 1, gt);
      this.pos = gt + 1;
    }
  }

  /** Finds the closing '>' of a tag, skipping '>' inside quoted attribute values. */
  private findTagEnd(from: number): number {
    const buf = this.buf;
    let i = from;
    for (;;) {
      const gt = buf.indexOf('>', i);
      if (gt < 0) return -1;
      // Look for a quote only inside this candidate tag (bounded: never scans the whole buffer).
      let q = -1;
      for (let k = i; k < gt; k++) {
        const c = buf.charCodeAt(k);
        if (c === 34 || c === 39) { q = k; break; }
      }
      if (q < 0) return gt;
      const close = buf.indexOf(buf.charAt(q), q + 1);
      if (close < 0) return -1;
      i = close + 1;
    }
  }

  private handleTag(start: number, end: number): void {
    const buf = this.buf;
    const first = buf.charCodeAt(start);
    if (first === 63 /* ? */ || first === 33 /* ! */) return;
    if (first === 47 /* / */) {
      this.endElement(buf.substring(start + 1, end).trim());
      return;
    }
    const selfClosing = buf.charCodeAt(end - 1) === 47;
    const contentEnd = selfClosing ? end - 1 : end;
    let nameEnd = start;
    while (nameEnd < contentEnd && !isWs(buf.charCodeAt(nameEnd))) nameEnd++;
    const name = buf.substring(start, nameEnd);
    this.startElement(name, nameEnd, contentEnd);
    if (selfClosing) this.endElement(name);
  }

  private attr(from: number, to: number, key: string): string | null {
    const buf = this.buf;
    let i = from;
    while (i < to) {
      while (i < to && isWs(buf.charCodeAt(i))) i++;
      const ks = i;
      while (i < to && buf.charCodeAt(i) !== 61 && !isWs(buf.charCodeAt(i))) i++;
      const keyMatches = i - ks === key.length && buf.startsWith(key, ks);
      while (i < to && isWs(buf.charCodeAt(i))) i++;
      if (i >= to || buf.charCodeAt(i) !== 61) { i++; continue; }
      i++;
      while (i < to && isWs(buf.charCodeAt(i))) i++;
      if (i >= to) return null;
      const q = buf.charAt(i);
      let value: string;
      if (q === '"' || q === "'") {
        let e = buf.indexOf(q, i + 1);
        if (e < 0 || e > to) e = to;
        value = keyMatches ? buf.substring(i + 1, e) : '';
        i = e + 1;
      } else {
        const s = i;
        while (i < to && !isWs(buf.charCodeAt(i))) i++;
        value = keyMatches ? buf.substring(s, i) : '';
      }
      if (keyMatches) return decodeEntities(value);
    }
    return null;
  }

  private startElement(name: string, attrsFrom: number, attrsTo: number): void {
    switch (name) {
      case 'programme': {
        this.inProgramme = true;
        this.pTitle = null;
        this.pDesc = null;
        const ch = this.attr(attrsFrom, attrsTo, 'channel');
        this.pChannel = ch;
        if (this.wantChannel != null && (ch == null || !this.wantChannel(ch))) {
          this.skipProgramme = true;
          return;
        }
        this.skipProgramme = false;
        const start = this.attr(attrsFrom, attrsTo, 'start');
        const stop = this.attr(attrsFrom, attrsTo, 'stop');
        this.pStart = start != null ? (parseXmltv(start) ?? 0) : 0;
        this.pStop = stop != null ? (parseXmltv(stop) ?? 0) : 0;
        break;
      }
      case 'channel':
        if (!this.inProgramme) {
          this.inChannel = true;
          this.cId = this.attr(attrsFrom, attrsTo, 'id');
          this.cName = null;
          this.cIcon = null;
        }
        break;
      case 'title':
        if (this.inProgramme && !this.skipProgramme && this.pTitle == null) this.beginCapture('title');
        break;
      case 'desc':
        if (this.inProgramme && !this.skipProgramme && this.pDesc == null) this.beginCapture('desc');
        break;
      case 'display-name':
        if (this.inChannel && this.cName == null) this.beginCapture('display-name');
        break;
      case 'icon':
        if (this.inChannel && this.cIcon == null) this.cIcon = this.attr(attrsFrom, attrsTo, 'src');
        break;
    }
  }

  private beginCapture(what: Capture): void {
    this.capture = what;
    this.text = '';
  }

  private endElement(name: string): void {
    if (this.capture != null && name === this.capture) {
      const decoded = decodeEntities(this.text.trim());
      const value = decoded.length > 0 ? decoded : null;
      if (this.capture === 'title') this.pTitle = value;
      else if (this.capture === 'desc') this.pDesc = value;
      else this.cName = value;
      this.capture = null;
      this.text = '';
      return;
    }
    if (name === 'programme') {
      const ch = this.pChannel;
      const title = this.pTitle;
      if (this.inProgramme && !this.skipProgramme && ch != null && title != null && this.pStart > 0 && this.pStop > this.pStart) {
        this.onProgramme({ channel: ch, start: this.pStart, stop: this.pStop, title, description: this.pDesc });
      }
      this.inProgramme = false;
      this.skipProgramme = false;
    } else if (name === 'channel') {
      if (this.inChannel) {
        if (this.cId != null) this.onChannel({ id: this.cId, displayName: this.cName, icon: this.cIcon });
        this.inChannel = false;
      }
    }
  }
}

export function decodeEntities(s: string): string {
  if (s.indexOf('&') < 0) return s;
  let out = '';
  let i = 0;
  let last = 0;
  while (i < s.length) {
    if (s.charCodeAt(i) === 38 /* & */) {
      const semi = s.indexOf(';', i + 1);
      if (semi >= i + 2 && semi <= i + 10) {
        const ent = s.substring(i + 1, semi);
        let decoded: string | null = null;
        if (ent === 'amp') decoded = '&';
        else if (ent === 'lt') decoded = '<';
        else if (ent === 'gt') decoded = '>';
        else if (ent === 'quot') decoded = '"';
        else if (ent === 'apos') decoded = "'";
        else if (ent.charAt(0) === '#') {
          const hex = ent.charAt(1) === 'x' || ent.charAt(1) === 'X';
          const digits = ent.substring(hex ? 2 : 1);
          const ok = hex ? /^[+-]?[0-9a-fA-F]+$/.test(digits) : /^[+-]?\d+$/.test(digits);
          if (ok) decoded = codePointToString(parseInt(digits, hex ? 16 : 10));
        }
        if (decoded != null) {
          out += s.substring(last, i) + decoded;
          i = semi + 1;
          last = i;
          continue;
        }
      }
    }
    i++;
  }
  return out + s.substring(last);
}

function codePointToString(cp: number): string {
  if (!(cp >= 0 && cp <= 0x10ffff)) return '';
  if (cp < 0x10000) return String.fromCharCode(cp);
  const v = cp - 0x10000;
  return String.fromCharCode(0xd800 + (v >> 10), 0xdc00 + (v & 0x3ff));
}

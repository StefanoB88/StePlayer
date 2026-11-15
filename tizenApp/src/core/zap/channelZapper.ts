// Port of zap/ChannelZapper.kt: next/previous in the current list (wrapping), "ultimo canale"
// toggle, and remote-control number entry ("2", "0", "1" → channel 201 after a short pause).
// It only decides WHICH channel; the player decides WHEN to open it (debounced).
import type { Channel, ChannelZapper as ChannelZapperApi } from '../api.ts';

export class ChannelZapper implements ChannelZapperApi {
  private list: Channel[];
  private cur: Channel;
  private last: Channel | null = null;
  private digits = '';
  private lastDigitAt = 0;
  private readonly numberEntryTimeoutMs: number;
  private readonly maxDigits: number;

  constructor(channels: Channel[], start: Channel, numberEntryTimeoutMs = 1_800, maxDigits = 4) {
    this.list = channels.length > 0 ? channels.slice() : [start];
    this.cur = start;
    this.numberEntryTimeoutMs = numberEntryTimeoutMs;
    this.maxDigits = maxDigits;
  }

  get current(): Channel { return this.cur; }

  /** The channel watched before current ("canale precedente"), if any. */
  get lastChannel(): Channel | null { return this.last; }

  /** Digits typed so far, for the on-screen "2 0 _" indicator. Empty when not typing. */
  get pendingNumber(): string { return this.digits; }

  updateList(channels: Channel[]): void {
    if (channels.length > 0) this.list = channels.slice();
  }

  next(): Channel { return this.moveBy(1); }
  previous(): Channel { return this.moveBy(-1); }

  private moveBy(delta: number): Channel {
    let idx = -1;
    for (let i = 0; i < this.list.length; i++) if (this.list[i].id === this.cur.id) { idx = i; break; }
    const n = this.list.length;
    const target = idx < 0 ? this.list[0] : this.list[(((idx + delta) % n) + n) % n];
    return this.select(target);
  }

  /** Toggles between the current and the previously watched channel. Null if none. */
  back(): Channel | null {
    return this.last ? this.select(this.last) : null;
  }

  /** Explicit selection (channel list, guide, search). */
  select(channel: Channel): Channel {
    if (channel.id !== this.cur.id) {
      this.last = this.cur;
      this.cur = channel;
    }
    this.clearNumber();
    return this.cur;
  }

  /**
   * Adds a digit typed on the remote. Returns true when the number is complete (max digits) and
   * should be committed immediately; otherwise call commitIfDue() from a timer.
   */
  typeDigit(digit: number, nowMs: number): boolean {
    if (!(digit >= 0 && digit <= 9 && Math.floor(digit) === digit)) throw new Error(`Cifra non valida: ${digit}`);
    if (this.digits.length > 0 && nowMs - this.lastDigitAt > this.numberEntryTimeoutMs) this.digits = '';
    if (this.digits.length === 0 && digit === 0) return false; // leading zero: ignore
    this.digits += String(digit);
    this.lastDigitAt = nowMs;
    return this.digits.length >= this.maxDigits;
  }

  /** The typed number if the pause after the last digit elapsed (or force); clears the entry. */
  commitIfDue(nowMs: number, force = false): number | null {
    if (this.digits.length === 0) return null;
    if (!force && nowMs - this.lastDigitAt < this.numberEntryTimeoutMs) return null;
    const n = parseInt(this.digits, 10);
    this.clearNumber();
    return isFinite(n) ? n : null;
  }

  /** Resolves a typed number against the current list first (by channel number, then position). */
  channelForNumber(n: number): Channel | null {
    const byNum = this.list.filter((c) => c.num === n)[0];
    if (byNum) return byNum;
    const byPos = this.list[n - 1];
    return byPos && this.list.every((c) => c.num == null) ? byPos : null;
  }

  clearNumber(): void {
    this.digits = '';
  }
}

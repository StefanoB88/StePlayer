// Remote key codes: Samsung (Tizen) and LG (webOS, Back = 461), plus desktop keyboard equivalents for development.
import { exitApp as platformExit } from './platform.ts';
export type Key =
  | 'up' | 'down' | 'left' | 'right' | 'enter' | 'back' | 'playPause' | 'play' | 'pause' | 'stop'
  | 'ff' | 'rw' | 'chUp' | 'chDown' | 'red' | 'green' | 'yellow' | 'blue' | 'info' | 'digit' | 'enterLong' | 'other';

export interface KeyPress { key: Key; digit: number; raw: KeyboardEvent }

export function mapKey(e: KeyboardEvent): KeyPress {
  const c = e.keyCode;
  let key: Key = 'other';
  let digit = -1;
  if (c === 38) key = 'up';
  else if (c === 40) key = 'down';
  else if (c === 37) key = 'left';
  else if (c === 39) key = 'right';
  else if (c === 13 || c === 65376) key = 'enter';
  else if (c === 10009 || c === 461 || c === 27 || (c === 8 && !isTyping(e))) key = 'back';
  else if (c === 10252 || (c === 32 && !isTyping(e))) key = 'playPause';
  else if (c === 415) key = 'play';
  else if (c === 19) key = 'pause';
  else if (c === 413) key = 'stop';
  else if (c === 417) key = 'ff';
  else if (c === 412) key = 'rw';
  else if (c === 427 || c === 33) key = 'chUp';
  else if (c === 428 || c === 34) key = 'chDown';
  else if (c === 403) key = 'red';
  else if (c === 404) key = 'green';
  else if (c === 405) key = 'yellow';
  else if (c === 406) key = 'blue';
  else if (c === 457 || (c === 73 && !isTyping(e))) key = 'info';
  else if (c >= 48 && c <= 57 && !isTyping(e)) { key = 'digit'; digit = c - 48; }
  else if (c >= 96 && c <= 105 && !isTyping(e)) { key = 'digit'; digit = c - 96; }
  return { key, digit, raw: e };
}

function isTyping(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null;
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
}

interface TizenGlobal {
  tvinputdevice?: { registerKeyBatch(keys: string[], ok?: () => void, err?: (e: unknown) => void): void };
  application?: { getCurrentApplication(): { exit(): void; hide(): void } };
}

export function tizen(): TizenGlobal | null {
  return (window as unknown as { tizen?: TizenGlobal }).tizen || null;
}

/** Media/colour/number keys are delivered to the app only after registration. */
export function registerRemoteKeys(): void {
  const t = tizen();
  if (!t || !t.tvinputdevice) return;
  const keys = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind',
    'ChannelUp', 'ChannelDown', 'ColorF0Red', 'ColorF1Green', 'ColorF2Yellow', 'ColorF3Blue', 'Info',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9'];
  try { t.tvinputdevice.registerKeyBatch(keys); } catch { /* older firmware: arrows/enter/back still work */ }
}

export function exitApp(): void { platformExit(); }

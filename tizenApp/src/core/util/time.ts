// Port of util/Time.kt.
import { toIntOrNull } from './text.ts';

export interface CivilDateTime {
  year: number; month: number; day: number; hour: number; minute: number; second: number;
}

/** Kotlin Long division truncates toward zero. */
function idiv(a: number, b: number): number {
  return Math.trunc(a / b);
}

function floorDiv(a: number, b: number): number {
  return Math.floor(a / b);
}

/** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
export function daysFromCivil(y0: number, m: number, d: number): number {
  const y = m <= 2 ? y0 - 1 : y0;
  const era = idiv(y >= 0 ? y : y - 399, 400);
  const yoe = y - era * 400;
  const mp = (m + 9) % 12;
  const doy = idiv(153 * mp + 2, 5) + d - 1;
  const doe = yoe * 365 + idiv(yoe, 4) - idiv(yoe, 100) + doy;
  return era * 146097 + doe - 719468;
}

export function civilFromDays(z0: number): [number, number, number] {
  const z = z0 + 719468;
  const era = idiv(z >= 0 ? z : z - 146096, 146097);
  const doe = z - era * 146097;
  const yoe = idiv(doe - idiv(doe, 1460) + idiv(doe, 36524) - idiv(doe, 146096), 365);
  const y = yoe + era * 400;
  const doy = doe - (365 * yoe + idiv(yoe, 4) - idiv(yoe, 100));
  const mp = idiv(5 * doy + 2, 153);
  const d = doy - idiv(153 * mp + 2, 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  return [m <= 2 ? y + 1 : y, m, d];
}

export function toEpochSeconds(dt: CivilDateTime, offsetSec = 0): number {
  return daysFromCivil(dt.year, dt.month, dt.day) * 86400 + dt.hour * 3600 + dt.minute * 60 + dt.second - offsetSec;
}

export function fromEpochSeconds(epochSec: number, offsetSec = 0): CivilDateTime {
  const local = epochSec + offsetSec;
  const days = floorDiv(local, 86400);
  const secOfDay = local - days * 86400;
  const [y, m, d] = civilFromDays(days);
  return {
    year: y, month: m, day: d,
    hour: idiv(secOfDay, 3600), minute: idiv(secOfDay % 3600, 60), second: secOfDay % 60,
  };
}

function isDigitCode(c: number): boolean {
  return c >= 48 && c <= 57;
}

/**
 * Parses XMLTV timestamps: "20240115183000 +0100", "20240115183000", "202401151830 -0500".
 * Returns epoch millis or null.
 */
export function parseXmltv(value: string): number | null {
  const s = value.trim();
  if (s.length < 12) return null;
  const num = (from: number, len: number): number | null =>
    from + len <= s.length ? toIntOrNull(s.substring(from, from + len)) : null;
  const year = num(0, 4); if (year == null) return null;
  const month = num(4, 2); if (month == null) return null;
  const day = num(6, 2); if (day == null) return null;
  const hour = num(8, 2); if (hour == null) return null;
  const minute = num(10, 2); if (minute == null) return null;
  const second = s.length >= 14 && isDigitCode(s.charCodeAt(12)) ? (num(12, 2) ?? 0) : 0;
  let offset = 0;
  let tzStart = -1;
  for (let i = 12; i < s.length; i++) {
    const c = s.charCodeAt(i);
    if (c === 43 || c === 45) { tzStart = i; break; }
  }
  if (tzStart > 0) {
    const sign = s.charCodeAt(tzStart) === 45 ? -1 : 1;
    let tz = '';
    for (let i = tzStart + 1; i < s.length; i++) if (isDigitCode(s.charCodeAt(i))) tz += s.charAt(i);
    if (tz.length >= 4) {
      const h = toIntOrNull(tz.substring(0, 2)) ?? 0;
      const m = toIntOrNull(tz.substring(2, 4)) ?? 0;
      offset = sign * (h * 3600 + m * 60);
    }
  }
  return toEpochSeconds({ year, month, day, hour, minute, second }, offset) * 1000;
}

/** Parses "2024-01-15 18:30:00" interpreted at the given offset. */
export function parseSqlDateTime(value: string, offsetSec = 0): number | null {
  const parts = value.trim().split(/[ T]/);
  const date = parts[0].split('-').map(toIntOrNull).filter((n): n is number => n != null);
  if (date.length !== 3) return null;
  const time = parts.length > 1 ? parts[1].split(':').map(toIntOrNull).filter((n): n is number => n != null) : [];
  return toEpochSeconds({
    year: date[0], month: date[1], day: date[2],
    hour: time[0] ?? 0, minute: time[1] ?? 0, second: time[2] ?? 0,
  }, offsetSec) * 1000;
}

export function nowMillis(): number {
  return Date.now();
}

export const HOUR = 60 * 60 * 1000;
export const DAY = 24 * HOUR;

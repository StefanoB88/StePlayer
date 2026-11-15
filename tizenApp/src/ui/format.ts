// Same output as the Android app's util/Format.kt.
const DAYS = ['dom', 'lun', 'mar', 'mer', 'gio', 'ven', 'sab'];
const MONTHS = ['gen', 'feb', 'mar', 'apr', 'mag', 'giu', 'lug', 'ago', 'set', 'ott', 'nov', 'dic'];
const two = (n: number) => (n < 10 ? `0${n}` : String(n));

export const Format = {
  /** "21:30" */
  clock(epochMs: number): string {
    const d = new Date(epochMs);
    return `${two(d.getHours())}:${two(d.getMinutes())}`;
  },
  /** "sab 14 nov, 21:30" */
  dayTime(epochMs: number): string {
    const d = new Date(epochMs);
    return `${DAYS[d.getDay()]} ${d.getDate()} ${MONTHS[d.getMonth()]}, ${Format.clock(epochMs)}`;
  },
  /** "14 nov 2026" */
  date(epochMs: number): string {
    const d = new Date(epochMs);
    return `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
  },
  /** 01:23:45 or 23:45 */
  duration(ms: number): string {
    const total = Math.max(0, Math.floor(ms / 1000));
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    return h > 0 ? `${h}:${two(m)}:${two(s)}` : `${two(m)}:${two(s)}`;
  },
  /** "45 min", "< 1 min", null when unknown or 0. */
  minutes(seconds: number | null | undefined): string | null {
    if (seconds == null || seconds <= 0) return null;
    if (seconds < 60) return '< 1 min';
    return `${Math.floor(seconds / 60)} min`;
  },
  /** Xtream uses a 0–10 scale; some servers send 0–5. "7,5" */
  rating(r: number | null | undefined): string | null {
    if (r == null || !(r > 0)) return null;
    return (r <= 5 ? r * 2 : r).toFixed(1).replace('.', ',');
  },
};

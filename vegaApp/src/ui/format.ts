// Times shown in the UI (same formats as the TV apps).
const two = (n: number) => (n < 10 ? `0${n}` : String(n));

/** "21:30" from epoch ms. */
export function clock(ms: number): string {
  const d = new Date(ms);
  return `${two(d.getHours())}:${two(d.getMinutes())}`;
}

/** "1:02:03" / "02:03" from a duration in ms. */
export function duration(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  return h > 0 ? `${h}:${two(m)}:${two(s % 60)}` : `${two(m)}:${two(s % 60)}`;
}

// Port of shared/util/ArtworkUrl.kt: ask the image CDN for the variant that fits the slot instead of the
// full-size original (2000–3000 px), which the TV would download and decode in software for a 300 px tile.

/** Widths the CDN serves for every image (poster and backdrop size lists combined). */
const WIDTHS = [92, 154, 185, 300, 342, 500, 780, 1280];
const PATTERN = /^(https?:\/\/image\.tmdb\.org\/t\/p\/)(original|w\d+)(\/[^?#]+.*)$/i;

/** `url` resized for a slot `widthPx` physical pixels wide; widthPx <= 0 means unknown (unchanged). */
export function sizedArtwork(url: string, widthPx: number): string {
  if (!(widthPx > 0)) return url;
  const m = PATTERN.exec(url);
  if (!m) return url;
  const [, prefix, current, rest] = m;
  let target = 0;
  for (const w of WIDTHS) if (w >= widthPx) { target = w; break; }
  if (!target) return url; // slot wider than any variant
  const currentWidth = current.toLowerCase() === 'original' ? Infinity : Number(current.slice(1));
  // Never ask for a larger variant than the list gave.
  return target < currentWidth ? `${prefix}w${target}${rest}` : url;
}

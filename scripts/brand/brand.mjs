// StePlayer brand: the "Origami" mark in the "Oro e notte" palette. Single source for every generated icon and
// store image (Android, iOS/tvOS, Samsung TV, website). The Android vectors (res/drawable/ic_brand.xml,
// ic_launcher_*.xml) and the UI palettes repeat these values by hand.

export const COLORS = {
  gold: '#FFD25C', // top facet, Accent2
  amber: '#FFA21F', // bottom facet, Accent
  burnt: '#D1650A', // folded facet, AccentDeep
  navyLight: '#1C2550', // background gradient, centre
  navy: '#0A0F26', // background gradient, edge
  text: '#F5F7FA',
};

/** The three facets in a 100×100 box (play shape, optical centre slightly right of the middle). */
export function facets(strokeWidth = 3) {
  const f = (d, c) => `<path d="${d}" fill="${c}" stroke="${c}" stroke-width="${strokeWidth}" stroke-linejoin="round"/>`;
  return f('M33 23 L77 50 L46 50 Z', COLORS.gold) + f('M33 77 L77 50 L46 50 Z', COLORS.amber) + f('M33 23 L46 50 L33 77 Z', COLORS.burnt);
}

/** CSS background matching the icon's navy radial gradient (for full-bleed images of any aspect ratio). */
export const NAVY_BG = `background:${COLORS.navy};background-image:radial-gradient(circle at 30% 20%, ${COLORS.navyLight} 0%, ${COLORS.navy} 75%)`;

/**
 * Icon SVG at `size` px. `rounded`: transparent corners (Samsung, website); false = full square (iOS and Play
 * apply their own mask). `background: false` = facets only on transparency (tvOS front layer).
 */
export function iconSvg(size, { rounded = false, background = true } = {}) {
  const bg = background
    ? `<defs><radialGradient id="bg" cx=".3" cy=".2" r="1"><stop offset="0" stop-color="${COLORS.navyLight}"/><stop offset="1" stop-color="${COLORS.navy}"/></radialGradient></defs>
       <rect width="100" height="100" rx="${rounded ? 23 : 0}" fill="url(#bg)"/>`
    : '';
  return `<svg width="${size}" height="${size}" viewBox="0 0 100 100" xmlns="http://www.w3.org/2000/svg">${bg}${facets()}</svg>`;
}

export const FONT = "-apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Helvetica Neue', Arial, sans-serif"; // single quotes: used inside style="…"

/** Mark + "StePlayer" wordmark, centred, for banners and wide images. `h` = image height in px. */
export function lockup(h, { markScale = 0.42, textScale = 0.2, gap = 0.07 } = {}) {
  return `<div style="display:flex;align-items:center;justify-content:center;gap:${Math.round(h * gap)}px;width:100%;height:100%">
    ${iconSvg(Math.round(h * markScale), { rounded: true })}
    <div style="font:800 ${Math.round(h * textScale)}px/1 ${FONT};letter-spacing:-0.02em;color:${COLORS.text}">StePlayer</div></div>`;
}

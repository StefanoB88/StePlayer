// StePlayer palette "Oro e notte" (scripts/brand/brand.mjs) and sizes. Vega lays React Native out in dp on a 960×540
// canvas: every value here is half the 1920×1080 pixel value of the Samsung/LG TV app.
export const C = {
  bg: '#0A0F26',
  surface: '#141B3A',
  elevated: '#1C2550',
  accent: '#FFA21F',
  gold: '#FFD25C',
  deep: '#D1650A',
  text: '#F5F7FA',
  text2: '#A9B1C7',
  live: '#FF4D5E',
  success: '#3DDC97',
  scrim: 'rgba(0,0,0,0.55)',
};

export const S = {
  /** Overscan-safe side margin. */
  margin: 28,
  railWidth: 92,
  radius: 12,
};

export const F = {
  display: { fontSize: 30, fontWeight: '800' as const, color: C.text },
  headline: { fontSize: 22, fontWeight: '700' as const, color: C.text },
  title: { fontSize: 15, fontWeight: '700' as const, color: C.text },
  body: { fontSize: 13, color: C.text },
  body2: { fontSize: 12, color: C.text2 },
  label: { fontSize: 11, fontWeight: '600' as const, color: C.text2 },
};

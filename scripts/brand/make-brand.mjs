// Renders every StePlayer icon and brand image from scripts/brand/brand.mjs with headless Chrome:
//   node scripts/brand/make-brand.mjs [ios] [android] [tizen] [web] [play]     (no argument = all)
// Outputs:
//   ios      iosApp/Resources/iOS AppIcon 1024, tvOS App Icon layers (400×240 @1x/@2x, 1280×768) and Top Shelf images
//   android  androidApp/src/main/res/drawable-xhdpi/tv_banner.png (Android TV banner, 320×180 dp)
//   tizen    tizenApp/static/icon.png (512×512)
//   web      ~/steplayerwebsite/assets/icon.png (192) and favicon.png (64) — override with WEBSITE_DIR
//   play     store/google-play/icon-512.png and feature-graphic-1024x500.png
// The Android launcher icon is vector (res/drawable/ic_launcher_*.xml) and is not rendered here.
import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { homedir } from 'node:os';
import { fileURLToPath } from 'node:url';
import { COLORS, FONT, NAVY_BG, iconSvg, lockup } from './brand.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '../..');
const puppeteer = createRequire(join(root, 'tizenApp/package.json'))('puppeteer-core');
const want = new Set(process.argv.slice(2));
const all = want.size === 0;

const browser = await puppeteer.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: 'new' });

async function png(file, w, h, body, transparent = false) {
  mkdirSync(dirname(file), { recursive: true });
  const p = await browser.newPage();
  await p.setViewport({ width: w, height: h, deviceScaleFactor: 1 });
  await p.setContent(`<!doctype html><html><head><style>*{margin:0;padding:0}html,body{width:${w}px;height:${h}px;overflow:hidden}
    svg{display:block}</style></head><body>${body}</body></html>`, { waitUntil: 'load' });
  await p.screenshot({ path: file, omitBackground: transparent, clip: { x: 0, y: 0, width: w, height: h } });
  await p.close();
  console.log(`${w}×${h}  ${file.replace(root + '/', '')}`);
}
const full = (w, h, inner = '') => `<div style="width:${w}px;height:${h}px;${NAVY_BG}">${inner}</div>`;
/** Mark only, centred, on the navy background, scaled by the image height. */
const markOn = (w, h, scale) => full(w, h, `<div style="display:flex;align-items:center;justify-content:center;width:100%;height:100%">${iconSvg(Math.round(h * scale), { background: false })}</div>`);

if (all || want.has('ios')) {
  const A = join(root, 'iosApp/Resources/iOS/Assets.xcassets/AppIcon.appiconset');
  await png(join(A, 'icon-1024.png'), 1024, 1024, iconSvg(1024));
  const B = join(root, 'iosApp/Resources/tvOS/Assets.xcassets/App Icon & Top Shelf Image.brandassets');
  for (const [stack, sizes] of [['App Icon', [[400, 240, ''], [800, 480, '@2x']]], ['App Icon - App Store', [[1280, 768, '']]]]) {
    for (const [w, h, sfx] of sizes) {
      // Parallax: navy background layer, facets on a transparent front layer (kept inside the safe area).
      await png(join(B, `${stack}.imagestack/Back.imagestacklayer/Content.imageset/back${sfx}.png`), w, h, full(w, h));
      await png(join(B, `${stack}.imagestack/Front.imagestacklayer/Content.imageset/front${sfx}.png`), w, h,
        `<div style="display:flex;align-items:center;justify-content:center;width:${w}px;height:${h}px">${iconSvg(Math.round(h * 0.62), { background: false })}</div>`, true);
    }
  }
  for (const [name, w, h] of [['Top Shelf Image', 1920, 720], ['Top Shelf Image Wide', 2320, 720]]) {
    await png(join(B, `${name}.imageset/shelf.png`), w, h, full(w, h, lockup(h, { markScale: 0.34, textScale: 0.16 })));
    await png(join(B, `${name}.imageset/shelf@2x.png`), w * 2, h * 2, full(w * 2, h * 2, lockup(h * 2, { markScale: 0.34, textScale: 0.16 })));
  }
}

if (all || want.has('android')) {
  await png(join(root, 'androidApp/src/main/res/drawable-xhdpi/tv_banner.png'), 640, 360, full(640, 360, lockup(360, { markScale: 0.4, textScale: 0.19 })));
}

if (all || want.has('tizen')) {
  await png(join(root, 'tizenApp/static/icon.png'), 512, 512, iconSvg(512, { rounded: true }), true);
}

if (all || want.has('web')) {
  const W = process.env.WEBSITE_DIR || join(homedir(), 'steplayerwebsite');
  await png(join(W, 'assets/icon.png'), 192, 192, iconSvg(192, { rounded: true }), true);
  await png(join(W, 'assets/favicon.png'), 64, 64, iconSvg(64, { rounded: true }), true);
}

if (all || want.has('play')) {
  const P = join(root, 'store/google-play');
  await png(join(P, 'icon-512.png'), 512, 512, iconSvg(512));
  await png(join(P, 'feature-graphic-1024x500.png'), 1024, 500, full(1024, 500, `
    <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100%;gap:22px">
      ${lockup(170, { markScale: 0.9, textScale: 0.5, gap: 0.14 }).replace('width:100%;height:100%', 'height:170px')}
      <div style="font:500 30px/1.2 ${FONT};color:${COLORS.gold};letter-spacing:.01em">Il lettore per le tue liste M3U</div>
    </div>`));
}

await browser.close();

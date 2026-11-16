// Generates the Samsung Seller Office images into ../store/samsung/ (sizes and limits from the Seller Office form):
//   logo-1920x1080.png            Logo asset, transparent, 32-bit PNG, ≤ 300 KB
//   icon-background-1920x1080.jpg Background for the logo asset, 24-bit, ≤ 300 KB
//   icon-512x423.png              Full-colour icon, ≤ 300 KB
//   background-1920x1080.jpg      App detail page background (left ~40 % covered by the TV's overlay), ≤ 2 MB
//   screenshot-1..3-1920x1080.jpg Screenshots, ≤ 500 KB each
// Screenshots use only the fictional dev catalogue (invented names, generated abstract artwork):
//   NO_EXPIRY=1 node dev/server.mjs 8080   (in another terminal; do not use REAL_ART)
//   node dev/store-assets.mjs
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, statSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { NAVY_BG, iconSvg } from '../../scripts/brand/brand.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, '../store/samsung');
const tmp = join(root, 'dev/shots/store');
const profile = join(tmp, 'profile');
const URL = process.env.URL || 'http://127.0.0.1:8080/';
rmSync(tmp, { recursive: true, force: true });
mkdirSync(tmp, { recursive: true });
mkdirSync(out, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: 'new', userDataDir: profile, args: ['--window-size=1920,1080'],
  defaultViewport: { width: 1920, height: 1080 },
});

/** PNG → JPEG under `maxKb`, lowering the quality until it fits. */
function toJpeg(png, jpg, maxKb) {
  for (const q of [90, 85, 80, 75, 70, 65, 60]) {
    execFileSync('sips', ['-s', 'format', 'jpeg', '-s', 'formatOptions', String(q), png, '--out', jpg], { stdio: 'ignore' });
    if (statSync(jpg).size <= maxKb * 1024) return q;
  }
  throw new Error(`${jpg} is still over ${maxKb} KB`);
}

// ---------------------------------------------------------------------------------------------- screenshots
const page = await browser.newPage();
await page.goto(URL, { waitUntil: 'load' });
async function keys(name, n = 1, gap = 200) { for (let i = 0; i < n; i++) { await page.keyboard.press(name); await sleep(gap); } }
async function type(text) { await page.keyboard.press('Enter'); await sleep(250); await page.keyboard.type(text, { delay: 15 }); await page.keyboard.press('Enter'); await sleep(250); }
/** Waits until every visible artwork image has loaded and faded in. */
async function settle(extra = 800) {
  await page.waitForFunction(() => Array.prototype.every.call(document.querySelectorAll('img'),
    (i) => !i.getBoundingClientRect().width || i.getBoundingClientRect().right < 0 || i.getBoundingClientRect().left > 1920 || (i.complete && i.style.opacity !== '0')), { timeout: 15000 }).catch(() => undefined);
  await sleep(extra);
}
async function railTo(index) { // 0 Cerca, 1 Home, 2 Live, 3 Film, 4 Serie
  await keys('ArrowLeft', 8, 120); // to the rail (Return on Home would exit the app)
  await keys('ArrowUp', 6, 120); await keys('ArrowDown', index, 150); await keys('Enter'); await sleep(2500);
}
async function shot(name) { const p = join(tmp, `${name}.png`); await page.screenshot({ path: p }); return p; }

// First launch: legal notice, then "Accesso al server" to the local dev server (fictional catalogue).
await sleep(2500);
await keys('Enter'); await keys('ArrowDown'); await keys('Enter'); await sleep(1500);
await keys('ArrowUp', 3); await keys('ArrowRight'); await keys('Enter'); await sleep(800);
await keys('ArrowDown', 3);
await type(URL.replace(/\/$/, ''));
await keys('ArrowDown'); await type('demo');
await keys('ArrowDown'); await type('demo');
await keys('ArrowDown', 2); await keys('Enter');
await page.waitForFunction(() => document.querySelector('.rail-item'), { timeout: 90000 });
await sleep(3000);

// 1. Home
await settle(1500);
const sHome = await shot('home');
// 2. Live: channels with now/next
await railTo(2);
await keys('ArrowRight', 2, 400); await keys('ArrowDown', 2, 300);
await settle();
const sLive = await shot('live');
// 3. Movie detail
await railTo(3);
await keys('ArrowRight'); await keys('ArrowRight'); await keys('Enter'); await sleep(2500);
await settle(1200);
const sMovie = await shot('movie');

const screenshots = [sHome, sLive, sMovie];
screenshots.forEach((p, i) => {
  const q = toJpeg(p, join(out, `screenshot-${i + 1}-1920x1080.jpg`), 500);
  console.log(`screenshot-${i + 1}: q${q}`);
});

// ---------------------------------------------------------------------------------------------- branding
const ICON_SVG = (size) => iconSvg(size, { rounded: true });
const BASE_CSS = `*{margin:0;padding:0;box-sizing:border-box} body{font-family:-apple-system,BlinkMacSystemFont,"SF Pro Display","Helvetica Neue",Arial,sans-serif;-webkit-font-smoothing:antialiased}`;
// Navy like the icon, with a warm glow from the accent.
const GLOW_BG = `${NAVY_BG.replace(/;background-image:/, ';background-image:radial-gradient(900px 700px at 80% 85%, rgba(255,162,31,.18), transparent 70%),')}`;

async function render(html, w, h, file, transparent = false) {
  const p = await browser.newPage();
  await p.setViewport({ width: w, height: h, deviceScaleFactor: 1 });
  await p.setContent(`<!doctype html><html><head><style>${BASE_CSS}</style></head><body>${html}</body></html>`, { waitUntil: 'load' });
  await p.screenshot({ path: file, omitBackground: transparent, clip: { x: 0, y: 0, width: w, height: h } });
  await p.close();
  return file;
}

// Logo asset: icon + wordmark, centred, transparent (Samsung composes it on the background for 16:9 and 1:1).
await render(`<div style="width:1920px;height:1080px;display:flex;align-items:center;justify-content:center;gap:56px">
  ${ICON_SVG(300)}<div style="font-size:150px;font-weight:800;letter-spacing:-4px;color:#fff">StePlayer</div></div>`,
1920, 1080, join(out, 'logo-1920x1080.png'), true);

// Background for the logo asset.
await render(`<div style="width:1920px;height:1080px;${GLOW_BG}"></div>`, 1920, 1080, join(tmp, 'icon-bg.png'));
toJpeg(join(tmp, 'icon-bg.png'), join(out, 'icon-background-1920x1080.jpg'), 300);

// 512×423 full-colour icon.
await render(`<div style="width:512px;height:423px;${GLOW_BG.replace(/900px 600px/, '420px 300px').replace(/900px 700px/, '420px 320px')};display:flex;flex-direction:column;align-items:center;justify-content:center;gap:22px">
  ${ICON_SVG(196)}<div style="font-size:58px;font-weight:800;letter-spacing:-1.5px;color:#fff">StePlayer</div></div>`,
512, 423, join(out, 'icon-512x423.png'));

// Detail page background: app UI on the right 60 % (the TV darkens the left 40 % for the app name and text).
const homeData = readFileSync(sHome).toString('base64');
await render(`<div style="width:1920px;height:1080px;position:relative;overflow:hidden;${GLOW_BG}">
  <div style="position:absolute;left:820px;top:150px;width:1380px;height:776px;border-radius:36px;overflow:hidden;
    box-shadow:0 40px 120px rgba(0,0,0,.6),0 0 0 2px rgba(255,255,255,.08);transform:perspective(2400px) rotateY(-14deg);transform-origin:left center">
    <img src="data:image/png;base64,${homeData}" style="width:100%;height:100%;display:block">
  </div>
  <div style="position:absolute;inset:0;background:linear-gradient(90deg,#0A0E1F 0%,rgba(10,14,31,.85) 36%,rgba(10,14,31,0) 62%)"></div>
</div>`, 1920, 1080, join(tmp, 'detail-bg.png'));
toJpeg(join(tmp, 'detail-bg.png'), join(out, 'background-1920x1080.jpg'), 2048);

await browser.close();
for (const f of ['logo-1920x1080.png', 'icon-background-1920x1080.jpg', 'icon-512x423.png', 'background-1920x1080.jpg',
  'screenshot-1-1920x1080.jpg', 'screenshot-2-1920x1080.jpg', 'screenshot-3-1920x1080.jpg']) {
  console.log(`${f.padEnd(34)} ${Math.round(statSync(join(out, f)).size / 1024)} KB`);
}

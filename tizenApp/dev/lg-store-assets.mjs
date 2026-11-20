// Generates the LG Seller Lounge images into ../store/lg/images/ from the webOS build:
//   screenshot-1..5-1920x1080.jpg  Home, Live, Guida TV, Film, Serie (≤ 1 MB each)
//   icon-400x400.png               Square store icon
// Screenshots use only the fictional dev catalogue (invented names, generated abstract artwork):
//   npm run build:webos && BUILD_DIR=build-webos NO_EXPIRY=1 node dev/server.mjs 8080   (in another terminal)
//   node dev/lg-store-assets.mjs
import puppeteer from 'puppeteer-core';
import { execFileSync } from 'node:child_process';
import { mkdirSync, rmSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { iconSvg } from '../../scripts/brand/brand.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const out = join(root, '../store/lg/images');
const tmp = join(root, 'dev/shots/lg-store');
const URL = process.env.URL || 'http://127.0.0.1:8080/';
rmSync(tmp, { recursive: true, force: true });
mkdirSync(tmp, { recursive: true });
mkdirSync(out, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: 'new', userDataDir: join(tmp, 'profile'), protocolTimeout: 60000,
  args: ['--window-size=1920,1080', '--autoplay-policy=no-user-gesture-required'],
  defaultViewport: { width: 1920, height: 1080 },
});

function toJpeg(png, jpg, maxKb) {
  for (const q of [92, 88, 84, 80, 75, 70]) {
    execFileSync('sips', ['-s', 'format', 'jpeg', '-s', 'formatOptions', String(q), png, '--out', jpg], { stdio: 'ignore' });
    if (statSync(jpg).size <= maxKb * 1024) return q;
  }
  throw new Error(`${jpg} is still over ${maxKb} KB`);
}

const page = await browser.newPage();
// The app thinks it runs on an LG TV (LG texts, webOS behaviour); the stubs answer like the TV would.
await page.evaluateOnNewDocument(() => {
  window.PalmSystem = { platformBack() {} };
  window.PalmServiceBridge = class { call() { setTimeout(() => this.onservicecallback('{"returnValue":true}'), 20); } };
});
await page.goto(URL, { waitUntil: 'load' });
async function keys(name, n = 1, gap = 200) { for (let i = 0; i < n; i++) { await page.keyboard.press(name); await sleep(gap); } }
async function type(text) { await page.keyboard.press('Enter'); await sleep(250); await page.keyboard.type(text, { delay: 15 }); await page.keyboard.press('Enter'); await sleep(250); }
async function settle(extra = 800) {
  await page.waitForFunction(() => Array.prototype.every.call(document.querySelectorAll('img'),
    (i) => !i.getBoundingClientRect().width || i.getBoundingClientRect().right < 0 || i.getBoundingClientRect().left > 1920 || (i.complete && i.style.opacity !== '0')), { timeout: 15000 }).catch(() => undefined);
  await sleep(extra);
}
async function railTo(index) { // 0 Cerca, 1 Home, 2 Live, 3 Film, 4 Serie
  await keys('ArrowLeft', 8, 120);
  await keys('ArrowUp', 6, 120); await keys('ArrowDown', index, 150); await keys('Enter'); await sleep(2500);
}
async function shot(name) { const p = join(tmp, `${name}.png`); await page.screenshot({ path: p }); return p; }

// First launch: terms, then "Inserisci con il telecomando" → "Accesso al server" to the local dev server.
await sleep(2500);
await keys('Enter'); await keys('ArrowDown'); await keys('ArrowRight'); await keys('Enter'); await sleep(1500); // tick, then "Ho capito e accetto" (right of "Non accetto")
await keys('ArrowUp', 3); await keys('ArrowRight'); await keys('Enter'); await sleep(800);
await keys('ArrowDown', 3);
await type(URL.replace(/\/$/, ''));
await keys('ArrowDown'); await type('demo');
await keys('ArrowDown'); await type('demo');
await keys('ArrowDown', 2); await keys('Enter');
await page.waitForFunction(() => document.querySelector('.rail-item'), { timeout: 90000 });
await sleep(3000);

const shots = [];
// 1. Home
await settle(1500);
shots.push(await shot('home'));
// 2. Live: channels with now/next
await railTo(2);
await keys('ArrowRight', 2, 400); await keys('ArrowDown', 2, 300);
await settle();
shots.push(await shot('live'));
// 3. Guida TV: icon button (title "Guida TV") in the Live top bar
const guide = await page.evaluate(() => {
  const e = document.querySelector('[data-focus][title="Guida TV"]');
  if (!e) return null;
  const r = e.getBoundingClientRect();
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
});
if (guide) {
  await page.mouse.click(guide.x, guide.y); await sleep(2500);
  await page.mouse.move(1919, 1079); await sleep(300); // pointer out of the way
  await settle(1000);
  shots.push(await shot('guide'));
  await keys('Escape'); await sleep(1200);
}
// 4. Film: poster grid
await railTo(3);
await settle(1200);
shots.push(await shot('movies'));
// 5. Serie: series page with episodes
await railTo(4);
await keys('ArrowRight'); await keys('ArrowRight'); await keys('Enter'); await sleep(2500);
await settle(1200);
shots.push(await shot('series'));

shots.forEach((p, i) => {
  const q = toJpeg(p, join(out, `screenshot-${i + 1}-1920x1080.jpg`), 1024);
  console.log(`screenshot-${i + 1}: q${q}`);
});

// Square store icon.
const icon = await browser.newPage();
await icon.setViewport({ width: 400, height: 400, deviceScaleFactor: 1 });
await icon.setContent(`<!doctype html><html><head><style>*{margin:0;padding:0}svg{display:block}</style></head><body>${iconSvg(400)}</body></html>`);
await icon.screenshot({ path: join(out, 'icon-400x400.png'), clip: { x: 0, y: 0, width: 400, height: 400 } });

await browser.close();
for (const f of [...shots.map((_, i) => `screenshot-${i + 1}-1920x1080.jpg`), 'icon-400x400.png']) {
  console.log(`${f.padEnd(30)} ${Math.round(statSync(join(out, f)).size / 1024)} KB`);
}

// Drives the app in headless Chrome at 1920x1080 like a TV remote and takes screenshots.
// Usage: node dev/shot.mjs [--fresh] "<steps>"
//   steps separated by ";": wait:ms | key:ArrowDown[*n] | enter | long | back | type:text | shot:name | log
import puppeteer from 'puppeteer-core';
import { mkdirSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const fresh = args.includes('--fresh');
const steps = (args.filter((a) => !a.startsWith('--'))[0] || 'wait:3000;shot:home').split(';').map((s) => s.trim()).filter(Boolean);
const shots = join(root, 'dev/shots');
const profile = join(shots, 'profile');
if (fresh) rmSync(profile, { recursive: true, force: true });
mkdirSync(shots, { recursive: true });

const browser = await puppeteer.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: 'new', userDataDir: profile, args: ['--window-size=1920,1080', '--autoplay-policy=no-user-gesture-required'],
  defaultViewport: { width: 1920, height: 1080 },
});
const page = await browser.newPage();
const logs = [];
page.on('console', (m) => logs.push(`[${m.type()}] ${m.text()}`));
page.on('pageerror', (e) => logs.push(`[pageerror] ${e.message}`));
await page.goto(process.env.URL || 'http://127.0.0.1:8080/', { waitUntil: 'load' });
for (const step of steps) {
  const [cmd, ...rest] = step.split(':');
  const arg = rest.join(':');
  if (cmd === 'wait') await new Promise((r) => setTimeout(r, Number(arg)));
  else if (cmd === 'key') {
    const [k, n] = arg.split('*');
    for (let i = 0; i < Number(n || 1); i++) { await page.keyboard.press(k); await new Promise((r) => setTimeout(r, 180)); }
  } else if (cmd === 'enter') { await page.keyboard.press('Enter'); await new Promise((r) => setTimeout(r, 250)); }
  else if (cmd === 'long') { await page.keyboard.down('Enter'); await new Promise((r) => setTimeout(r, 900)); await page.keyboard.up('Enter'); }
  else if (cmd === 'back') { await page.keyboard.press('Escape'); await new Promise((r) => setTimeout(r, 250)); }
  else if (cmd === 'type') { await page.keyboard.type(arg, { delay: 20 }); }
  else if (cmd === 'shot') { await page.screenshot({ path: join(shots, `${arg}.png`) }); console.log(`shot ${arg}`); }
  else if (cmd === 'log') { console.log(logs.splice(0).join('\n')); }
}
const errors = logs.filter((l) => /error/i.test(l));
if (errors.length) console.log(errors.join('\n'));
await browser.close();

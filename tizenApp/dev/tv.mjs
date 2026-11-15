// Remote-drives StePlayer on a real TV through the Chrome DevTools protocol (app launched with `sdb shell 0 debug`)
// and measures smoothness: frame times, long tasks, layout/style counts, heap.
// Usage: node dev/tv.mjs <port> "<steps>"
//   steps: wait:ms | key:Name[*n] (Up Down Left Right Enter Back Red ChUp ChDown 0-9) | long | shot:name
//          | mark:label (prints frame stats since the previous mark) | eval:js | reload
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const port = process.argv[2];
const steps = (process.argv[3] || '').split(';').map((s) => s.trim()).filter(Boolean);
const shots = join(root, 'dev/shots/tv');
mkdirSync(shots, { recursive: true });

const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
const ws = new WebSocket(targets.find((t) => t.type === 'page').webSocketDebuggerUrl);
await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
let id = 0;
const pending = new Map();
const traceEvents = [];
let traceDone = null;
ws.onmessage = (m) => {
  const msg = JSON.parse(typeof m.data === 'string' ? m.data : m.data.toString());
  if (msg.method === 'Tracing.dataCollected') traceEvents.push(...msg.params.value);
  if (msg.method === 'Tracing.tracingComplete' && traceDone) traceDone();
  if (msg.id && pending.has(msg.id)) { pending.get(msg.id)(msg); pending.delete(msg.id); }
};
const send = (method, params = {}) => new Promise((r) => { const i = ++id; pending.set(i, r); ws.send(JSON.stringify({ id: i, method, params })); });
const evaluate = async (expr) => {
  const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.result && r.result.exceptionDetails) return { error: r.result.exceptionDetails.exception ? r.result.exceptionDetails.exception.description : r.result.exceptionDetails.text };
  return r.result && r.result.result ? r.result.result.value : r;
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const KEYS = { Up: 38, Down: 40, Left: 37, Right: 39, Enter: 13, Back: 10009, Red: 403, ChUp: 427, ChDown: 428, Info: 457, PlayPause: 10252 };
async function key(name, holdMs = 0) {
  const code = KEYS[name] ?? (/^\d$/.test(name) ? 48 + Number(name) : 0);
  await send('Input.dispatchKeyEvent', { type: 'rawKeyDown', windowsVirtualKeyCode: code, nativeVirtualKeyCode: code, key: name });
  if (holdMs) await sleep(holdMs);
  await send('Input.dispatchKeyEvent', { type: 'keyUp', windowsVirtualKeyCode: code, nativeVirtualKeyCode: code, key: name });
}

// Frame monitor inside the page.
const MONITOR = `(() => {
  if (window.__fm) return 'ok';
  const fm = window.__fm = { frames: [], longTasks: [], last: performance.now() };
  const loop = (t) => { fm.frames.push(t - fm.last); fm.last = t; requestAnimationFrame(loop); };
  requestAnimationFrame(loop);
  try { new PerformanceObserver((l) => l.getEntries().forEach((e) => fm.longTasks.push(Math.round(e.duration)))).observe({ entryTypes: ['longtask'] }); } catch (e) {}
  return 'installed';
})()`;
const STATS = `(() => {
  const fm = window.__fm; const f = fm.frames.splice(0); const lt = fm.longTasks.splice(0);
  if (!f.length) return null;
  const s = f.slice().sort((a, b) => a - b);
  const pct = (p) => Math.round(s[Math.min(s.length - 1, Math.floor(s.length * p))]);
  const janky = f.filter((x) => x > 34).length;
  return { frames: f.length, avgFps: Math.round(1000 / (f.reduce((a, b) => a + b, 0) / f.length)), p50: pct(0.5), p95: pct(0.95), max: Math.round(s[s.length - 1]), janky, longTasks: lt.length, longestTask: lt.length ? Math.max(...lt) : 0 };
})()`;

await send('Runtime.enable');
await send('Performance.enable');
console.log('monitor', await evaluate(MONITOR));
let metricsBase = null;
async function metrics() {
  const r = await send('Performance.getMetrics');
  const m = Object.fromEntries(r.result.metrics.map((x) => [x.name, x.value]));
  return m;
}
metricsBase = await metrics();
for (const step of steps) {
  const [cmd, ...rest] = step.split(':');
  const arg = rest.join(':');
  if (cmd === 'wait') await sleep(Number(arg));
  else if (cmd === 'key') { const [k, n] = arg.split('*'); for (let i = 0; i < Number(n || 1); i++) { await key(k); await sleep(Number(process.env.KEYGAP || 220)); } }
  else if (cmd === 'long') await key('Enter', 900);
  else if (cmd === 'reload') { await send('Page.reload', { ignoreCache: true }); await sleep(3500); await evaluate(MONITOR); }
  else if (cmd === 'tab') console.log('tab', await evaluate(`(function(){var a=document.querySelectorAll('.rail-item');for(var i=0;i<a.length;i++)if(a[i].textContent.trim()===${JSON.stringify(arg)}){a[i].click();return 'ok'}return 'missing'})()`));
  else if (cmd === 'eval') console.log(JSON.stringify(await evaluate(arg)));
  else if (cmd === 'jsfile') {
    const { readFileSync } = await import('node:fs');
    console.log(JSON.stringify(await evaluate(readFileSync(join(root, arg), 'utf8'))));
  }
  else if (cmd === 'trace') {
    if (arg === 'start') {
      traceEvents.length = 0;
      await send('Tracing.start', { categories: 'devtools.timeline,disabled-by-default-devtools.timeline,blink,cc,gpu,v8', transferMode: 'ReportEvents' });
    } else {
      const done = new Promise((r) => { traceDone = r; });
      await send('Tracing.end');
      await done;
      writeFileSync(join(shots, `${arg}.json`), JSON.stringify(traceEvents));
      // Summary: complete events on the renderer main thread, by name.
      const threads = {};
      for (const e of traceEvents) if (e.ph === 'M' && e.name === 'thread_name') threads[`${e.pid}:${e.tid}`] = e.args.name;
      const totals = {};
      for (const e of traceEvents) {
        if (e.ph !== 'X' || !e.dur) continue;
        const th = threads[`${e.pid}:${e.tid}`] || '?';
        const k = `${th} | ${e.name}`;
        totals[k] = (totals[k] || 0) + e.dur / 1000;
      }
      Object.entries(totals).sort((a, b) => b[1] - a[1]).slice(0, 30).forEach(([k, v]) => console.log(v.toFixed(0).padStart(7), 'ms', k));
    }
  }
  else if (cmd === 'css') { // css:on (uses $CSS) | css:off
    const expr = arg === 'off'
      ? "(function(){var e=document.getElementById('__exp');if(e)e.remove();return 'off'})()"
      : `(function(){var e=document.getElementById('__exp')||document.createElement('style');e.id='__exp';e.textContent=${JSON.stringify(process.env.CSS || '')};document.head.appendChild(e);return 'on'})()`;
    console.log('css', await evaluate(expr));
  }
  else if (cmd === 'seq') { // seq:Down*4,Right*12  (runs dev/tvseq.js inside the page)
    const { readFileSync } = await import('node:fs');
    const fn = readFileSync(join(root, 'dev/tvseq.js'), 'utf8');
    const r = await evaluate(`(${fn.trim().replace(/;\s*$/, '')})(${JSON.stringify(arg.split(',').join(';'))}, ${Number(process.env.KEYGAP || 250)})`);
    console.log(typeof r === 'string' ? r : JSON.stringify(r));
  }
  else if (cmd === 'shot') {
    const r = await send('Page.captureScreenshot', { format: 'png' });
    if (r.result) { writeFileSync(join(shots, `${arg}.png`), Buffer.from(r.result.data, 'base64')); console.log(`shot ${arg}`); }
  } else if (cmd === 'mark') {
    const s = await evaluate(STATS);
    const m = await metrics();
    const d = (k) => Math.round((m[k] - (metricsBase[k] || 0)) * (k.endsWith('Duration') ? 1000 : 1));
    console.log(`[${arg}]`, JSON.stringify(s), `script=${d('ScriptDuration')}ms layout=${d('LayoutDuration')}ms style=${d('RecalcStyleDuration')}ms layouts=${d('LayoutCount')} heap=${Math.round(m.JSHeapUsedSize / 1048576)}MB nodes=${m.Nodes}`);
    metricsBase = m;
  }
}
ws.close();

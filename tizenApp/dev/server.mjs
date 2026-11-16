// Development server: serves build/ and a fictional test source (M3U list + XMLTV + a fake API Xtream server),
// so the TV app can be tried in a desktop browser. Every name is invented; media = dev/media/sample.mp4.
// Usage: node dev/server.mjs [port]   →  M3U: http://127.0.0.1:8080/dev/list.m3u
//                                       API Xtream: server http://127.0.0.1:8080  user "demo"  password "demo"
import { createServer } from 'node:http';
import { existsSync, readFileSync, statSync, createReadStream } from 'node:fs';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const port = Number(process.argv[2] || 8080);
// BIND=0.0.0.0 PUBLIC=http://<mac-ip>:8080 lets a TV on the LAN use it; SCALE=n multiplies the catalog;
// REAL_ART=1 serves realistic JPEG artwork (dev/media/posters) instead of light SVGs.
const BIND = process.env.BIND || '127.0.0.1';
const HOST = process.env.PUBLIC || `http://127.0.0.1:${port}`;
const SCALE = Number(process.env.SCALE || 1);
const REAL_ART = process.env.REAL_ART === '1';
// NO_EXPIRY=1: no expiry date (store screenshots without the expiry notice).
const NO_EXPIRY = process.env.NO_EXPIRY === '1';
// PNG_ART=1: the same generated artwork as PNG (iOS and Android cannot decode SVG), rendered once with Chrome and cached.
const PNG_ART = process.env.PNG_ART === '1';
const ART_EXT = PNG_ART ? 'png' : 'svg';
const MEDIA = join(root, 'dev/media/sample.mp4');

const CATS = [
  { id: '1', name: 'Generalisti' }, { id: '2', name: 'Notizie' }, { id: '3', name: 'Sport' },
];
// Invented names only (store screenshots are taken from this catalogue): no real channel, film or series titles.
const CHANNEL_NAMES = ['Zefiro', 'Ninfea', 'Lumen Uno', 'Corallo', 'Quarzo', 'Vespero', 'Brezza', 'Altamira',
  'Notiziario Uno', 'Notiziario Due', 'Cronache Demo', 'Dispaccio', 'Stadio Demo', 'Pista Uno', 'Traguardo', 'Motore Zero'];
// Invented places, combined into names for every item beyond the hand-written ones.
const PLACES = ['Vetrano', 'Asterio', 'Lirea', 'Calvisella', 'Senara', 'Oriela', 'Valdibrina', 'Ferandi', 'Marzolano', 'Coralba',
  'Ostrena', 'Lisenna', 'Torbella', 'Quiriano', 'Saltena', 'Movena', 'Arcella', 'Pradello'];
const CH_WORDS = ['Aurora', 'Sirio', 'Levante', 'Ponente', 'Ambra', 'Corvina', 'Lince', 'Ginepro', 'Opale', 'Rovere', 'Tamerice', 'Vela'];
const MOVIE_PATTERNS = ['Le notti di %', 'Il ritorno a %', 'Una estate a %', 'Il segreto di %', 'Lettere da %', 'La strada per %'];
const SERIES_PATTERNS = ['Le cronache di %', 'Misteri a %', 'La casa di %', 'Ritorno a %'];
const fill = (pattern, i) => pattern.replace('%', PLACES[i % PLACES.length]);
const channels = Array.from({ length: 40 * SCALE }, (_, i) => ({
  id: String(100 + i), num: i + 1, name: i < CHANNEL_NAMES.length ? CHANNEL_NAMES[i] : `${CH_WORDS[i % CH_WORDS.length]} ${Math.floor(i / CH_WORDS.length) + 1}`,
  cat: CATS[i < 8 ? 0 : i < 12 ? 1 : i < 16 ? 2 : i % 3].id, epg: `ch${i + 1}.esempio`, archive: i % 4 === 0 ? 3 : 0,
}));
const MOVIE_TITLES = ['Le lanterne di Vetrano', 'Nebbia su Borgo Asterio', 'Il faro di Punta Lirea', 'Ottobre a Calvisella', 'La corsa di Elvira Mantis',
  'Sale e vento a Oriela', 'Il cartografo di Senara', 'Notturno a Valdibrina', 'Le isole Ferandi', 'Il ponte di Marzolano', 'Estate a Coralba', 'I giardini di Ostrena'];
const movies = Array.from({ length: 36 * SCALE }, (_, i) => ({
  id: String(500 + i), name: i < MOVIE_TITLES.length ? MOVIE_TITLES[i] : fill(MOVIE_PATTERNS[i % MOVIE_PATTERNS.length], i * 7), cat: String(10 + (i % 3)),
  year: 2015 + (i % 10), rating: 5 + (i % 5), added: Math.floor(Date.now() / 1000) - i * 86400,
}));
const SERIES_TITLES = ['I giorni di Valmora', 'Oltre Cimarosso', 'La bottega di Ondina', 'Codice Azzurrite', 'Isola Terenzia', 'Le stagioni di Ferla'];
const series = Array.from({ length: 12 * SCALE }, (_, i) => ({
  id: String(800 + i), name: i < SERIES_TITLES.length ? SERIES_TITLES[i] : fill(SERIES_PATTERNS[i % SERIES_PATTERNS.length], i * 5 + 3), cat: String(20 + (i % 2)),
  year: 2018 + (i % 6), rating: 6 + (i % 4),
}));
const VOD_CATS = [{ id: '10', name: 'Avventura' }, { id: '11', name: 'Commedia' }, { id: '12', name: 'Drammatico' }];
const SERIES_CATS = [{ id: '20', name: 'Gialli' }, { id: '21', name: 'Famiglia' }];

const pngCache = new Map();
let pngBrowser = null;
async function svgToPng(key, svg) {
  if (pngCache.has(key)) return pngCache.get(key);
  const job = (async () => {
    if (!pngBrowser) {
      const { default: puppeteer } = await import('puppeteer-core');
      pngBrowser = puppeteer.launch({ executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: 'new' });
    }
    const b = await pngBrowser;
    const w = Number(/width="(\d+)"/.exec(svg)[1]); const h = Number(/height="(\d+)"/.exec(svg)[1]);
    const page = await b.newPage();
    await page.setViewport({ width: w, height: h });
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);
    const buf = await page.screenshot({ type: 'png', omitBackground: true, clip: { x: 0, y: 0, width: w, height: h } });
    await page.close();
    return Buffer.from(buf);
  })();
  pngCache.set(key, job);
  return job;
}

const COLORS = ['#7C5CFF', '#00D4FF', '#FF3B5C', '#2ED47A', '#FFB020', '#4B6BFB', '#E05FD0', '#1FB5A8'];
function svgPoster(title, i, w = 400, hgt = 600) {
  const c1 = COLORS[i % COLORS.length], c2 = COLORS[(i + 3) % COLORS.length];
  const words = title.split(' ');
  const lines = [];
  for (const w2 of words) { if (lines.length && (lines[lines.length - 1] + ' ' + w2).length < 12) lines[lines.length - 1] += ' ' + w2; else lines.push(w2); }
  const text = lines.map((l, k) => `<text x="50%" y="${hgt * 0.62 + k * 52}" text-anchor="middle" font-family="Helvetica" font-weight="700" font-size="44" fill="#fff">${l}</text>`).join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${hgt}"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="${c1}"/><stop offset="1" stop-color="${c2}"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><circle cx="${w * 0.7}" cy="${hgt * 0.25}" r="${w * 0.22}" fill="rgba(255,255,255,0.18)"/>${text}</svg>`;
}
function svgLogo(name, i) {
  const c = COLORS[i % COLORS.length];
  const initials = name.split(' ').map((p) => p[0]).join('').slice(0, 3).toUpperCase();
  return `<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect x="20" y="30" width="260" height="140" rx="28" fill="${c}"/><text x="150" y="122" text-anchor="middle" font-family="Helvetica" font-weight="800" font-size="64" fill="#fff">${initials}</text></svg>`;
}
function svgBackdrop(title, i) { return svgPoster(title, i + 2, 1280, 720).replace(/font-size="44"/g, 'font-size="0"'); }

function logoUrl(i) { return REAL_ART ? `${HOST}/dev/img/l${i % 16}.jpg?c=${i}` : `${HOST}/dev/logo/${i}.${ART_EXT}`; }
function posterUrl(k, i) { return REAL_ART ? `${HOST}/dev/img/p${i % 24}.jpg?${k}=${i}` : `${HOST}/dev/poster/${k}${i}.${ART_EXT}`; }
function backdropUrl(k, i) { return REAL_ART ? `${HOST}/dev/img/b${i % 8}.jpg?${k}=${i}` : `${HOST}/dev/backdrop/${k}${i}.${ART_EXT}`; }
function m3u() {
  let s = `#EXTM3U url-tvg="${HOST}/dev/epg.xml"\n`;
  for (const [i, c] of channels.entries()) {
    const cat = CATS.find((x) => x.id === c.cat).name;
    s += `#EXTINF:-1 tvg-id="${c.epg}" tvg-chno="${c.num}" tvg-logo="${logoUrl(i)}" group-title="${cat}"${c.archive ? ` catchup="default" catchup-days="${c.archive}"` : ''},${c.name}\n${HOST}/dev/live/${c.id}.ts\n`;
  }
  for (const [i, m] of movies.entries()) {
    const cat = VOD_CATS.find((x) => x.id === m.cat).name;
    s += `#EXTINF:-1 tvg-logo="${posterUrl('m', i)}" group-title="Film ${cat}",${m.name} (${m.year})\n${HOST}/dev/movie/${m.id}.mp4\n`;
  }
  for (const [i, se] of series.entries()) {
    for (let e = 1; e <= 4; e++) s += `#EXTINF:-1 tvg-logo="${posterUrl('s', i)}" group-title="Serie",${se.name} S01E0${e}\n${HOST}/dev/series/${se.id}/1/${e}.mp4\n`;
  }
  return s;
}

function xmltvTime(ms) {
  const d = new Date(ms);
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}00 +0000`;
}
const SHOWS = ['Buongiorno a tutti', 'Notiziario', 'Cucina facile', 'Documentario natura', 'Quiz della sera', 'Film del pomeriggio',
  'Viaggi nel mondo', 'Musica live', 'Approfondimento', 'Telefilm', 'Meteo', 'Talk show'];
function epg() {
  const hour = 3_600_000;
  const start = Math.floor(Date.now() / hour) * hour - 24 * hour;
  let s = '<?xml version="1.0" encoding="UTF-8"?>\n<tv>\n';
  for (const c of channels) s += `<channel id="${c.epg}"><display-name>${c.name}</display-name></channel>\n`;
  for (const [ci, c] of channels.entries()) {
    let t = start;
    let k = ci;
    while (t < start + 72 * hour) {
      const len = (30 + ((k * 17) % 4) * 30) * 60_000;
      const title = SHOWS[k % SHOWS.length];
      s += `<programme start="${xmltvTime(t)}" stop="${xmltvTime(t + len)}" channel="${c.epg}"><title>${title}</title><desc>Puntata di prova di ${title.toLowerCase()}.</desc></programme>\n`;
      t += len; k++;
    }
  }
  return s + '</tv>\n';
}

// ----- fake API Xtream (player_api.php)
function xtream(q) {
  const action = q.get('action');
  const now = Math.floor(Date.now() / 1000);
  if (q.get('username') !== 'demo' || q.get('password') !== 'demo') return { user_info: { auth: 0 } };
  switch (action) {
    case null: case '': return {
      user_info: { auth: 1, username: 'demo', status: 'Active', exp_date: NO_EXPIRY ? null : String(now + 2 * 86400), max_connections: '1', active_cons: '0', is_trial: '0', allowed_output_formats: ['m3u8', 'ts'] },
      server_info: { url: '127.0.0.1', port: String(port), timestamp_now: now, timezone: 'Europe/Rome' },
    };
    case 'get_live_categories': return CATS.map((c) => ({ category_id: c.id, category_name: c.name, parent_id: 0 }));
    case 'get_vod_categories': return VOD_CATS.map((c) => ({ category_id: c.id, category_name: c.name, parent_id: 0 }));
    case 'get_series_categories': return SERIES_CATS.map((c) => ({ category_id: c.id, category_name: c.name, parent_id: 0 }));
    case 'get_live_streams': return channels.map((c, i) => ({ num: c.num, name: c.name, stream_type: 'live', stream_id: Number(c.id), stream_icon: logoUrl(i), epg_channel_id: c.epg, added: String(now), category_id: c.cat, tv_archive: c.archive ? 1 : 0, tv_archive_duration: c.archive }));
    case 'get_vod_streams': return movies.map((m, i) => ({ num: i + 1, name: m.name, stream_type: 'movie', stream_id: Number(m.id), stream_icon: posterUrl('m', i), rating: String(m.rating), rating_5based: m.rating / 2, added: String(m.added), category_id: m.cat, container_extension: 'mp4', year: String(m.year) }));
    case 'get_series': return series.map((s, i) => ({ num: i + 1, name: s.name, series_id: Number(s.id), cover: posterUrl('s', i), plot: `Trama di prova della serie ${s.name}.`, genre: 'Drammatico', releaseDate: `${s.year}-01-01`, rating: String(s.rating), category_id: s.cat, last_modified: String(now - i * 3600) }));
    case 'get_vod_info': {
      const m = movies.find((x) => x.id === q.get('vod_id')) || movies[0];
      const i = movies.indexOf(m);
      return { info: { name: m.name, plot: `Trama di prova del film ${m.name}. Una storia inventata per provare l'app.`, genre: 'Avventura', cast: 'Attore Uno, Attrice Due', director: 'Regista Tre', releasedate: `${m.year}-05-01`, duration_secs: 5400, backdrop_path: [backdropUrl('m', i)], rating: String(m.rating) }, movie_data: { stream_id: Number(m.id), name: m.name, container_extension: 'mp4' } };
    }
    case 'get_series_info': {
      const s = series.find((x) => x.id === q.get('series_id')) || series[0];
      const i = series.indexOf(s);
      const ep = (season) => Array.from({ length: 6 }, (_, k) => ({ id: `${s.id}${season}${k + 1}`, episode_num: k + 1, title: `Episodio ${k + 1}`, container_extension: 'mp4', season, info: { plot: `Episodio di prova ${k + 1}.`, duration_secs: 2700, movie_image: backdropUrl('s', i) } }));
      return { info: { name: s.name, cover: posterUrl('s', i), plot: `Trama di prova della serie ${s.name}.`, cast: 'Attore Uno', director: 'Regista Due', genre: 'Drammatico', backdrop_path: [backdropUrl('s', i)] }, seasons: [{ season_number: 1 }, { season_number: 2 }], episodes: { 1: ep(1), 2: ep(2) } };
    }
    case 'get_short_epg': case 'get_simple_data_table': return { epg_listings: [] };
    default: return [];
  }
}

const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png', '.xml': 'application/xml', '.svg': 'image/svg+xml' };

function sendMedia(req, res) {
  if (!existsSync(MEDIA)) { res.writeHead(404); res.end('no media'); return; }
  const size = statSync(MEDIA).size;
  const range = req.headers.range;
  if (range) {
    const m = /bytes=(\d+)-(\d*)/.exec(range);
    const a = Number(m[1]);
    const b = m[2] ? Math.min(Number(m[2]), size - 1) : size - 1;
    res.writeHead(206, { 'Content-Type': 'video/mp4', 'Content-Range': `bytes ${a}-${b}/${size}`, 'Content-Length': b - a + 1, 'Accept-Ranges': 'bytes', 'Access-Control-Allow-Origin': '*' });
    createReadStream(MEDIA, { start: a, end: b }).pipe(res);
  } else {
    res.writeHead(200, { 'Content-Type': 'video/mp4', 'Content-Length': size, 'Accept-Ranges': 'bytes', 'Access-Control-Allow-Origin': '*' });
    createReadStream(MEDIA).pipe(res);
  }
}

createServer((req, res) => {
  const url = new URL(req.url, HOST);
  const p = url.pathname;
  const cors = { 'Access-Control-Allow-Origin': '*' };
  const send = (code, body, type) => { res.writeHead(code, { 'Content-Type': type, ...cors }); res.end(body); };
  if (p === '/player_api.php') return send(200, JSON.stringify(xtream(url.searchParams)), 'application/json');
  if (p === '/xmltv.php' || p === '/dev/epg.xml') return send(200, epg(), 'application/xml');
  if (p === '/dev/list.m3u' || p === '/get.php') return send(200, m3u(), 'audio/x-mpegurl');
  let m;
  if ((m = /^\/dev\/img\/([pbl]\d+\.jpg)$/.exec(p))) { const f = join(root, 'dev/media/posters', m[1]); if (existsSync(f)) { res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'max-age=86400', ...cors }); res.end(readFileSync(f)); return; } }
  let svg = null;
  if ((m = /^\/dev\/logo\/(\d+)\.(svg|png)$/.exec(p))) svg = svgLogo(channels[Number(m[1])]?.name || 'TV', Number(m[1]));
  else if ((m = /^\/dev\/poster\/([ms])(\d+)\.(svg|png)$/.exec(p))) { const i = Number(m[2]); const t = m[1] === 'm' ? movies[i]?.name : series[i]?.name; svg = svgPoster(t || 'Titolo', i); }
  else if ((m = /^\/dev\/backdrop\/([ms])(\d+)\.(svg|png)$/.exec(p))) svg = svgBackdrop('x', Number(m[2]));
  if (svg) {
    if (!p.endsWith('.png')) return send(200, svg, 'image/svg+xml');
    svgToPng(p, svg).then((buf) => { res.writeHead(200, { 'Content-Type': 'image/png', 'Cache-Control': 'max-age=86400', ...cors }); res.end(buf); },
      () => send(500, 'render failed', 'text/plain'));
    return;
  }
  if (/^\/(dev\/)?(live|movie|series|timeshift)\//.test(p) || /^\/demo\/demo\//.test(p)) return sendMedia(req, res);
  // build/ static files
  const file = join(root, 'build', p === '/' ? 'index.html' : p.replace(/^\/+/, ''));
  if (file.startsWith(join(root, 'build')) && existsSync(file) && statSync(file).isFile()) {
    return send(200, readFileSync(file), TYPES[extname(file)] || 'application/octet-stream');
  }
  send(404, 'not found', 'text/plain');
}).listen(port, BIND, () => console.log(`StePlayer TV dev server: ${HOST}  (M3U ${HOST}/dev/list.m3u, API Xtream ${HOST} demo/demo)`));

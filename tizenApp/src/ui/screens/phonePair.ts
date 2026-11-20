// "Usa il telefono" panel of "Aggiungi lista" (TV). The phone (StePlayer app → "Invia una lista alla TV") serves
// its lists on the local network; the TV finds it and asks for them with the 4-digit code shown on the phone.
import type { SourceInput } from '../../core/api.ts';
import { claimLists, parseAddress, probePhone, scanForPhones, subnetOf, xhrHttp, type Phone } from '../../core/pairing/phoneLink.ts';
import { app } from '../app.ts';
import { h, icon, clear } from '../dom.ts';
import { ElementsZone } from '../focus.ts';
import { textField } from '../input.ts';
import { prefs } from '../prefs.ts';
import { errorMessage } from './onboarding.ts';
import { platformLocalIp } from '../platform.ts';

export interface PhonePanel {
  el: HTMLElement;
  zones: ElementsZone[];
  /** Digit keys of the remote. */
  typeDigit(d: number): void;
  destroy(): void;
}

function tvIp(): string | null {
  const ip = platformLocalIp(); // Samsung network API / LG connection manager
  if (ip) return ip;
  // Development in a desktop browser opened on the LAN address.
  return subnetOf(location.hostname) ? location.hostname : null;
}

export function phonePanel(onAdded: (firstId: number) => void, onFocusChange: () => void): PhonePanel {
  const http = xhrHttp();
  let digits = '';
  let phones: Phone[] = [];
  let manual: Phone | null = null;
  let alive = true;
  let busy = false;
  let rescanTimer = 0;
  let showAddress = false;

  const boxes = h('div', { class: 'row', style: 'justify-content:center;margin:8px 0 28px' });
  const status = h('div', { class: 'row t-body-m c2', style: 'justify-content:center;min-height:48px;text-align:center' });
  const keypad = h('div', { class: 'keypad' });
  const extra = h('div', { class: 'col', style: 'align-items:center;margin-top:24px' });
  const el = h('div', { class: 'col', style: 'align-items:center' },
    h('div', { class: 't-headline-s', style: 'margin-bottom:24px;text-align:center' }, 'Configura con il telefono'),
    h('div', { class: 'col t-body-l', style: 'max-width:1100px' },
      h('div', {}, '1. Sul telefono apri StePlayer → Impostazioni → Invia una lista alla TV'),
      h('div', { style: 'margin-top:8px' }, '2. Tieni telefono e televisore sulla stessa rete Wi-Fi'),
      h('div', { style: 'margin-top:8px' }, '3. Inserisci qui il codice che vedi sul telefono')),
    boxes, status, keypad, extra);

  const keypadZone = new ElementsZone(keypad);
  const extraZone = new ElementsZone(extra);

  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', 'del', '0', 'ok'];
  for (const k of keys) {
    const b = h('div', { class: 'key s105', 'data-focus': true, 'data-default': k === '1' || null },
      k === 'del' ? icon('arrow_back', 44) : k === 'ok' ? icon('check', 48) : k);
    b.addEventListener('click', () => {
      if (k === 'del') { digits = digits.slice(0, -1); renderBoxes(); }
      else if (k === 'ok') submit();
      else typeDigit(Number(k));
    });
    keypad.appendChild(b);
  }

  function renderBoxes() {
    clear(boxes);
    for (let i = 0; i < 4; i++) {
      boxes.appendChild(h('div', { class: `codebox${i === digits.length ? ' active' : ''}` }, digits[i] || ''));
    }
  }

  function setStatus(text: string, kind: 'info' | 'ok' | 'error' | 'busy' = 'info') {
    clear(status);
    if (kind === 'busy') status.appendChild(h('div', { class: 'spinner small', style: 'margin-right:20px' }));
    if (kind === 'ok') status.appendChild(h('span', { style: 'color:var(--success);margin-right:16px' }, icon('check_circle', 40)));
    if (kind === 'error') status.appendChild(h('span', { style: 'color:var(--live);margin-right:16px' }, icon('error_outline', 40)));
    status.appendChild(h('span', { style: kind === 'error' ? 'color:var(--text)' : '' }, text));
  }

  function renderExtra() {
    clear(extra);
    if (!showAddress) {
      const b = h('div', { class: 'textbtn s104', 'data-focus': true }, "Il telefono non viene trovato? Inserisci l'indirizzo");
      b.addEventListener('click', () => { showAddress = true; renderExtra(); onFocusChange(); });
      extra.appendChild(b);
      return;
    }
    const f = textField({ label: "Indirizzo mostrato sul telefono (es. 192.168.1.23:47620)", type: 'url' });
    f.el.style.width = '900px';
    f.input.addEventListener('change', async () => {
      const a = parseAddress(f.value);
      if (!a) { setStatus('Indirizzo non valido', 'error'); return; }
      setStatus('Collegamento al telefono…', 'busy');
      manual = await probePhone(http, a.host, a.port);
      if (manual) { prefs.set('lastPhone', manual.host); setStatus(`Telefono trovato: ${manual.device}`, 'ok'); }
      else setStatus('Nessuna risposta: controlla che sul telefono sia aperta «Invia una lista alla TV»', 'error');
    });
    extra.appendChild(f.el);
  }

  async function scan() {
    if (!alive) return;
    const ip = tvIp();
    const subnet = subnetOf(ip);
    const last = prefs.get('lastPhone');
    if (!subnet) {
      if (last) {
        const p = await probePhone(http, last);
        if (p) { phones = [p]; setStatus(`Telefono trovato: ${p.device}`, 'ok'); return; }
      }
      setStatus("Inserisci l'indirizzo mostrato sul telefono", 'info');
      return;
    }
    if (!phones.length) setStatus('Cerco il telefono sulla rete…', 'busy');
    const found = await scanForPhones(http, subnet, {
      prefer: last ? [last] : [],
      isCancelled: () => !alive,
      onFound: (p) => { if (!busy) setStatus(`Telefono trovato: ${p.device}`, 'ok'); },
    });
    if (!alive) return;
    phones = found;
    if (found.length) { prefs.set('lastPhone', found[0].host); return; }
    if (!busy && !manual) setStatus('Nessun telefono trovato: apri «Invia una lista alla TV» sul telefono', 'info');
    rescanTimer = window.setTimeout(scan, 5000);
  }

  function typeDigit(d: number) {
    if (busy || digits.length >= 4) return;
    digits += String(d);
    renderBoxes();
    if (digits.length === 4) submit();
  }

  async function submit() {
    if (busy || digits.length < 4) return;
    const candidates = manual ? [manual, ...phones] : phones;
    if (!candidates.length) {
      setStatus('Telefono non ancora trovato: apri «Invia una lista alla TV» sul telefono e riprova', 'error');
      return;
    }
    busy = true;
    setStatus('Ricevo la lista dal telefono…', 'busy');
    let lists: SourceInput[] | null = null;
    let wrong = false;
    for (const p of candidates) {
      const r = await claimLists(http, p, digits);
      if (r.ok) { lists = r.lists; break; }
      if (r.reason === 'wrong-code') wrong = true;
    }
    if (!alive) return;
    if (!lists) {
      busy = false;
      digits = '';
      renderBoxes();
      setStatus(wrong ? 'Codice errato: controlla il codice sul telefono' : 'Il telefono non ha risposto: genera un nuovo codice sul telefono', 'error');
      return;
    }
    setStatus('Connessione in corso…', 'busy');
    const ids: number[] = [];
    let lastError: unknown = null;
    for (const input of lists) {
      try { ids.push(await app().core.sources.add(input)); } catch (e) { lastError = e; }
    }
    busy = false;
    if (!alive) return;
    if (!ids.length) {
      digits = '';
      renderBoxes();
      setStatus(errorMessage(lastError), 'error');
      return;
    }
    try { await app().core.sources.setActive(ids[0]); } catch { /* first added is already active */ }
    setStatus(ids.length > 1 ? `${ids.length} liste ricevute` : 'Lista ricevuta', 'ok');
    onAdded(ids[0]);
  }

  renderBoxes();
  renderExtra();
  scan();

  return {
    el,
    zones: [keypadZone, extraZone],
    typeDigit,
    destroy() { alive = false; window.clearTimeout(rescanTimer); },
  };
}

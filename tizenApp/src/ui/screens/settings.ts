// Settings (Android ui/settings/Settings.kt + PlaybackSettings.kt), adapted to the TV: no decoder/frame-rate
// options (the Samsung player always decodes in hardware and matches the frame rate itself).
import type { BufferMode, Category, ContentType, PlayerSettings, Source, SubtitleMode, SubtitleStyle } from '../../core/api.ts';
import { app, type Screen } from '../app.ts';
import { h, icon, clear } from '../dom.ts';
import { ElementsZone, FocusScope, makeTrack } from '../focus.ts';
import { confirmDialog, secondaryButton } from '../components.ts';
import { Format } from '../format.ts';
import { session } from '../session.ts';
import { addSourceScreen, DISCLAIMER, errorMessage, syncScreen, welcomeScreen } from './onboarding.ts';
import { openShell } from '../shell.ts';

const VERSION = '1.0.0';
export const PREFERRED_LANGUAGES: [string | null, string][] = [
  [null, 'Lingua del dispositivo'], ['it', 'Italiano'], ['en', 'English'], ['es', 'Español'], ['fr', 'Français'],
  ['de', 'Deutsch'], ['pt', 'Português'], ['ro', 'Română'], ['ar', 'العربية'],
];

function sectionBox(title: string, rows: (HTMLElement | null)[]): HTMLElement {
  return h('div', { style: 'margin-bottom:48px' },
    h('div', { class: 't-title-m c2', style: 'margin:0 0 16px 16px' }, title),
    h('div', { style: 'background:var(--surface);border-radius:36px;padding:12px 0' }, rows));
}

function settingRow(iconName: string, title: string, subtitle?: string | null, onClick?: () => void, busy = false): HTMLElement {
  const row = h('div', { class: `row${onClick ? ' s102' : ''}`, 'data-focus': onClick ? true : null, style: 'margin:0 12px;padding:24px 24px;border-radius:24px' },
    h('span', { style: 'color:var(--accent);margin-right:32px' }, icon(iconName, 44)),
    h('div', { class: 'col grow' }, h('div', { class: 't-body-l' }, title), subtitle ? h('div', { class: 't-body-s c2' }, subtitle) : null),
    busy ? h('div', { class: 'spinner small' }) : null);
  if (onClick) row.addEventListener('click', onClick);
  return row;
}

function switchRow(iconName: string, title: string, subtitle: string, checked: boolean, onChange: (v: boolean) => void): HTMLElement {
  const knob = h('div', { style: `position:absolute;top:6px;left:${checked ? 46 : 6}px;width:44px;height:44px;border-radius:50%;background:${checked ? '#fff' : 'var(--text2)'}` });
  const track = h('div', { style: `position:relative;width:96px;height:56px;border-radius:999px;flex:0 0 auto;background:${checked ? 'var(--accent)' : 'var(--outline)'}` }, knob);
  const row = h('div', { class: 'row s102', 'data-focus': true, style: 'margin:0 12px;padding:20px 24px;border-radius:24px' },
    h('span', { style: 'color:var(--accent);margin-right:32px' }, icon(iconName, 44)),
    h('div', { class: 'col grow' }, h('div', { class: 't-body-l' }, title), h('div', { class: 't-body-s c2' }, subtitle)), track);
  row.addEventListener('click', () => onChange(!checked));
  return row;
}

/** Chip-style single choice (Android ChoiceRow). */
function choiceRow<T>(title: string, options: [T, string][], selected: T, onSelect: (v: T) => void): HTMLElement {
  const chips = h('div', { class: 'row', style: 'flex-wrap:wrap;margin-top:16px' });
  for (const [v, label] of options) {
    const c = h('div', { class: `chip s104${v === selected ? ' selected' : ''}`, 'data-focus': true, style: 'margin-bottom:16px' }, label);
    c.addEventListener('click', () => onSelect(v));
    chips.appendChild(c);
  }
  return h('div', { style: 'padding:20px 36px' }, h('div', { class: 't-body-l' }, title), chips);
}

function topBarWithBack(title: string): { el: HTMLElement; zone: ElementsZone } {
  const back = h('div', { class: 'iconbtn s110', 'data-focus': true }, icon('arrow_back', 48));
  back.addEventListener('click', () => app().pop());
  const el = h('div', { class: 'topbar' }, back, h('div', { class: 'title t-title-l' }, title));
  return { el, zone: new ElementsZone(el) };
}

export function settingsScreen(): Screen {
  const core = app().core;
  const top = topBarWithBack('Impostazioni');
  const column = h('div', { style: 'width:1400px;margin:0 auto;padding:16px 0 96px' });
  const scroller = h('div', { style: 'position:absolute;left:0;right:0;top:128px;bottom:0;overflow:hidden' }, column);
  makeTrack(scroller);
  const el = h('div', { class: 'screen' }, top.el, scroller);
  const zone = new ElementsZone(column, { scroller, axis: 'y', pad: 200 });
  const scope = new FocusScope([zone, top.zone]);
  let syncing = false;
  let epgBusy = false;
  let expiry = true;
  let ps: PlayerSettings | null = null;
  let firstRender = true;

  const updatePlayer = async (fn: (s: PlayerSettings) => PlayerSettings) => { await core.playerPreferences.update(fn); ps = await core.playerPreferences.current(); render(); };

  async function render() {
    await session.reload();
    if (!ps) ps = await core.playerPreferences.current().catch(() => null);
    expiry = await core.reminders.enabled().catch(() => true);
    const focusedIndex = zone.current ? zone.items().indexOf(zone.current) : -1;
    clear(column);
    const active = session.source;

    column.appendChild(sectionBox('Liste', [
      ...session.sources.map((s) => sourceRow(s, active ? active.id === s.id : false)),
      settingRow('add', 'Aggiungi lista', null, () => app().push(addSourceScreen({
        canGoBack: true,
        onSaved: (id) => { app().pop(); app().push(syncScreen(id, () => { app().pop(); session.reload(); render(); })); },
      }))),
    ]));

    if (active) {
      const lastSync = active.lastSyncAt ? Format.dayTime(active.lastSyncAt) : 'mai';
      const rows: (HTMLElement | null)[] = [
        settingRow('refresh', 'Sincronizza ora', syncing ? 'Sincronizzazione…' : `Ultima sincronizzazione: ${lastSync}`, () => syncNow(active.id), syncing),
        settingRow('calendar_view_week', 'Aggiorna EPG', active.lastEpgSyncAt ? `Ultima sincronizzazione: ${Format.dayTime(active.lastEpgSyncAt)}` : null, () => refreshEpg(active), epgBusy),
        active.type === 'XTREAM' ? settingRow('info', 'Account', [
          `Scadenza: ${active.expiresAt ? Format.date(active.expiresAt) : 'illimitata'}`,
          active.maxConnections != null ? `Connessioni massime: ${active.maxConnections}` : null,
        ].filter(Boolean).join('  ·  ')) : null,
        settingRow('live_tv', 'Categorie visibili · Live', 'Nascondi le categorie che non ti interessano', () => app().push(categoriesScreen(active.id, 'LIVE'))),
        settingRow('movie', 'Categorie visibili · Film', null, () => app().push(categoriesScreen(active.id, 'MOVIE'))),
        settingRow('video_library', 'Categorie visibili · Serie', null, () => app().push(categoriesScreen(active.id, 'SERIES'))),
      ];
      column.appendChild(sectionBox(active.name, rows));
    }

    if (ps) {
      const s = ps;
      column.appendChild(sectionBox('Riproduzione', [
        choiceRow('Chiedi se stai ancora guardando', [[0, 'Mai'], [2, '2 ore'], [3, '3 ore'], [4, '4 ore'], [6, '6 ore']], s.stillWatchingAfterHours,
          (v) => updatePlayer((x) => ({ ...x, stillWatchingAfterHours: v }))),
        choiceRow<BufferMode>('Buffer', [['LARGE', 'Grande (predefinito)'], ['AUTO', 'Automatico'], ['NORMAL', 'Normale']], s.bufferMode,
          (v) => updatePlayer((x) => ({ ...x, bufferMode: v }))),
        choiceRow('Lingua audio preferita', PREFERRED_LANGUAGES, s.preferredAudioLanguages[0] || null,
          (v) => updatePlayer((x) => ({ ...x, preferredAudioLanguages: v ? [v] : [] }))),
        switchRow('info', 'Mostra statistiche nel player', 'Statistiche', s.showStatsOverlay, (v) => updatePlayer((x) => ({ ...x, showStatsOverlay: v }))),
        settingRow('closed_caption', 'Sottotitoli', null, () => app().push(subtitlesScreen())),
      ]));
    }

    column.appendChild(sectionBox('Avvisi di scadenza', [
      switchRow('info', 'Avvisi di scadenza', 'Ti avvisiamo 3 giorni e 1 giorno prima della scadenza comunicata dal provider.', expiry, async (v) => {
        await core.reminders.setEnabled(v); render();
      }),
    ]));
    column.appendChild(sectionBox('Note legali', [
      settingRow('info', DISCLAIMER),
      settingRow('lock', 'Privacy', 'StePlayer non raccoglie alcun dato. Liste, preferiti e cronologia restano solo su questo televisore.'),
      settingRow('info', 'StePlayer non è affiliato con alcun provider e non fornisce contenuti, liste o abbonamenti.'),
      settingRow('info', 'Informativa privacy e termini d\'uso', 'stefanob88.github.io/steplayerwebsite'),
    ]));
    column.appendChild(sectionBox('Informazioni', [settingRow('info', 'StePlayer', `Versione ${VERSION}`)]));

    if (focusedIndex >= 0 && scope.current === zone) {
      const items = zone.items();
      const e = items[Math.min(focusedIndex, items.length - 1)];
      if (e) zone.focusElement(e);
    } else if (firstRender) {
      scope.focus(zone); // the list arrives asynchronously: start on its first item, not on the back arrow
    }
    firstRender = false;
  }

  function sourceRow(s: Source, isActive: boolean): HTMLElement {
    const host = s.type === 'XTREAM' ? s.url.replace(/^[a-z]+:\/\//i, '').split('/')[0] : 'lista';
    const row = h('div', { class: 'row', style: 'padding:20px 36px' },
      h('div', { class: 'col grow' },
        h('div', { class: 'row' }, h('span', { class: 't-body-l' }, s.name),
          isActive ? h('span', { style: 'color:var(--success);margin-left:16px' }, icon('check_circle', 32)) : null),
        h('div', { class: 't-body-s c2' }, `${s.type === 'XTREAM' ? 'Server' : 'M3U'} · ${host}`)));
    if (!isActive) row.appendChild(secondaryButton('Usa', async () => {
      await core.sources.setActive(s.id);
      await session.reload();
      core.sync.syncIfStale(s.id).catch(() => undefined);
      render();
    }));
    const edit = h('div', { class: 'iconbtn s110', 'data-focus': true, style: 'color:var(--text2);margin-left:8px' }, icon('edit', 48));
    edit.addEventListener('click', () => app().push(addSourceScreen({
      editId: s.id, canGoBack: true, onSaved: () => { app().pop(); render(); },
    })));
    const del = h('div', { class: 'iconbtn s110', 'data-focus': true, style: 'color:var(--text2)' }, icon('delete', 48));
    del.addEventListener('click', () => confirmDialog('Elimina',
      `Eliminare la lista “${s.name}”? Preferiti e progressi di questa lista verranno rimossi.`, 'Elimina', async () => {
        await core.sources.delete(s.id);
        await session.reload();
        if (!session.sources.length) {
          app().reset(welcomeScreen(() => app().push(addSourceScreen({
            canGoBack: false, onSaved: (id) => app().reset(syncScreen(id, () => openShell())),
          }))));
          return;
        }
        render();
      }));
    row.append(edit, del);
    return row;
  }

  async function syncNow(id: number) {
    if (syncing) return;
    syncing = true; render();
    try { await core.sync.sync(id); app().toast('Sincronizzazione completata'); } catch (e) { app().toast(errorMessage(e)); }
    syncing = false; render();
  }

  async function refreshEpg(s: Source) {
    if (epgBusy) return;
    epgBusy = true; render();
    try { await core.epg.refresh(s); app().toast('Guida TV aggiornata'); } catch (e) { app().toast(errorMessage(e)); }
    epgBusy = false; render();
  }

  render();
  return { el, scope, onShow: () => render() };
}

function categoriesScreen(sourceId: number, type: ContentType): Screen {
  const core = app().core;
  const title = type === 'LIVE' ? 'Live' : type === 'MOVIE' ? 'Film' : 'Serie';
  const top = topBarWithBack(`Categorie visibili · ${title}`);
  const column = h('div', { style: 'width:1400px;margin:0 auto;padding:16px 0 96px' });
  const scroller = h('div', { style: 'position:absolute;left:0;right:0;top:128px;bottom:0;overflow:hidden' }, column);
  makeTrack(scroller);
  const el = h('div', { class: 'screen' }, top.el, scroller);
  const zone = new ElementsZone(column, { scroller, axis: 'y', pad: 200 });
  const scope = new FocusScope([zone, top.zone]);
  let cats: Category[] = [];

  async function render() {
    cats = await core.catalog.allCategories(sourceId, type).catch(() => []);
    const idx = zone.current ? zone.items().indexOf(zone.current) : -1;
    clear(column);
    for (const c of cats) {
      const on = !c.hidden;
      const knob = h('div', { style: `position:absolute;top:6px;left:${on ? 46 : 6}px;width:44px;height:44px;border-radius:50%;background:${on ? '#fff' : 'var(--text2)'}` });
      const row = h('div', { class: 'row s102', 'data-focus': true, style: 'background:var(--surface);border-radius:24px;padding:16px 32px;margin-bottom:8px' },
        h('div', { class: 't-body-l grow ellipsis' }, c.name),
        h('div', { style: `position:relative;width:96px;height:56px;border-radius:999px;background:${on ? 'var(--accent)' : 'var(--outline)'}` }, knob));
      row.addEventListener('click', async () => { await core.catalog.setCategoryHidden(sourceId, type, c, !c.hidden); render(); });
      column.appendChild(row);
    }
    if (idx >= 0 && scope.current === zone) { const e = zone.items()[idx]; if (e) zone.focusElement(e); }
    else scope.ensure(zone);
  }
  render();
  return { el, scope };
}

function subtitlesScreen(): Screen {
  const core = app().core;
  const top = topBarWithBack('Sottotitoli');
  const column = h('div', { style: 'width:1400px;margin:0 auto;padding:16px 0 96px' });
  const scroller = h('div', { style: 'position:absolute;left:0;right:0;top:128px;bottom:0;overflow:hidden' }, column);
  makeTrack(scroller);
  const el = h('div', { class: 'screen' }, top.el, scroller);
  const zone = new ElementsZone(column, { scroller, axis: 'y', pad: 200 });
  const scope = new FocusScope([zone, top.zone]);

  async function render() {
    const s = await core.playerPreferences.current();
    const st = s.subtitleStyle;
    const idx = zone.current ? zone.items().indexOf(zone.current) : -1;
    const set = async (fn: (x: PlayerSettings) => PlayerSettings) => { await core.playerPreferences.update(fn); render(); };
    clear(column);
    const preview = h('div', { style: 'height:240px;border-radius:32px;margin-bottom:40px;background:linear-gradient(135deg,#2b3456,#121831);display:flex;align-items:flex-end;justify-content:center;padding-bottom:32px' },
      h('span', { style: subtitleCss(st) }, 'Questo è un esempio di sottotitolo'));
    column.append(preview, sectionBox('Sottotitoli', [
      choiceRow<SubtitleMode>('Modalità', [['OFF', 'Disattivati'], ['AUTO', "Automatici (se l'audio è in un'altra lingua)"], ['ALWAYS', 'Sempre']], s.subtitleMode, (v) => set((x) => ({ ...x, subtitleMode: v }))),
      choiceRow('Lingua', PREFERRED_LANGUAGES, s.preferredSubtitleLanguage, (v) => set((x) => ({ ...x, preferredSubtitleLanguage: v }))),
      choiceRow('Dimensione', [[0.75, '75%'], [1, '100%'], [1.25, '125%'], [1.5, '150%'], [2, '200%']], st.scale, (v) => set((x) => ({ ...x, subtitleStyle: { ...x.subtitleStyle, scale: v } }))),
      choiceRow<SubtitleStyle['color']>('Colore', [['WHITE', 'Bianco'], ['YELLOW', 'Giallo']], st.color, (v) => set((x) => ({ ...x, subtitleStyle: { ...x.subtitleStyle, color: v } }))),
      choiceRow<SubtitleStyle['background']>('Sfondo del testo', [['NONE', 'Nessuno'], ['SEMI_TRANSPARENT', 'Semitrasparente'], ['OPAQUE', 'Pieno']], st.background, (v) => set((x) => ({ ...x, subtitleStyle: { ...x.subtitleStyle, background: v } }))),
      choiceRow<SubtitleStyle['edge']>('Bordo', [['NONE', 'Nessuno'], ['OUTLINE', 'Contorno'], ['SHADOW', 'Ombra']], st.edge, (v) => set((x) => ({ ...x, subtitleStyle: { ...x.subtitleStyle, edge: v } }))),
    ]));
    if (idx >= 0 && scope.current === zone) { const e = zone.items()[idx]; if (e) zone.focusElement(e); }
    else scope.ensure(zone);
  }
  render();
  return { el, scope };
}

/** Inline style for rendered subtitles (player + preview). */
export function subtitleCss(st: PlayerSettings['subtitleStyle']): string {
  const size = Math.round(44 * st.scale);
  const color = st.color === 'YELLOW' ? '#FFFF00' : '#FFFFFF';
  const bg = st.background === 'OPAQUE' ? 'rgba(0,0,0,1)' : st.background === 'SEMI_TRANSPARENT' ? 'rgba(0,0,0,0.5)' : 'transparent';
  const shadow = st.edge === 'OUTLINE' ? '-2px -2px 0 #000, 2px -2px 0 #000, -2px 2px 0 #000, 2px 2px 0 #000' : st.edge === 'SHADOW' ? '3px 3px 6px rgba(0,0,0,0.9)' : 'none';
  return `font-size:${size}px;line-height:1.3;color:${color};background:${bg};text-shadow:${shadow};padding:4px 16px;border-radius:8px;white-space:pre-line;text-align:center`;
}

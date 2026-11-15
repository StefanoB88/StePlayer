// Main shell: navigation rail (Android NavRail on TV: Cerca, Home, Live, Film, Serie … Impostazioni) + tabs.
// Tabs are created once and kept (state preserved across switches, like rememberSaveableStateHolder).
import { app, type Screen } from './app.ts';
import { h, icon } from './dom.ts';
import { ElementsZone, FocusScope, type Zone } from './focus.ts';
import type { KeyPress } from './keys.ts';
import { exitApp } from './keys.ts';
import { actions } from './actions.ts';
import { homeTab } from './screens/home.ts';
import { liveTab } from './screens/live.ts';
import { vodTab } from './screens/vod.ts';

export interface Tab {
  el: HTMLElement;
  zones: Zone[];
  defaultZone(): Zone | null;
  onShow?(): void;
  onHide?(): void;
  onKey?(k: KeyPress): boolean;
  /** Back while focus is inside the tab: true when handled. */
  onBack?(): boolean;
  destroy?(): void;
}

export type TabId = 'home' | 'live' | 'movies' | 'series';

const TABS: { id: TabId; label: string; icon: string }[] = [
  { id: 'home', label: 'Home', icon: 'home' },
  { id: 'live', label: 'Live', icon: 'live_tv' },
  { id: 'movies', label: 'Film', icon: 'movie' },
  { id: 'series', label: 'Serie', icon: 'video_library' },
];

export class Shell implements Screen {
  el: HTMLElement;
  scope = new FocusScope();
  private rail: HTMLElement;
  private railZone: ElementsZone;
  private content: HTMLElement;
  private tabs = new Map<TabId, Tab>();
  private items = new Map<TabId, HTMLElement>();
  selected: TabId = 'home';
  /** The tab was opened but its content wasn't loaded yet: focus it as soon as it can take the focus. */
  private pendingContentFocus = true;

  constructor() {
    this.rail = h('div', { class: 'rail' }, h('div', { class: 'logo' }, 'S'));
    this.rail.appendChild(this.railItem('search', 'Cerca', () => actions.openSearch()));
    for (const t of TABS) {
      const item = this.railItem(t.icon, t.label, () => this.select(t.id, true));
      this.items.set(t.id, item);
      this.rail.appendChild(item);
    }
    this.rail.appendChild(h('div', { class: 'rail-spacer' }));
    this.rail.appendChild(this.railItem('settings', 'Impostazioni', () => actions.openSettings()));
    this.content = h('div', { class: 'shell-content' });
    this.el = h('div', { class: 'screen shell' }, this.rail, this.content);
    this.railZone = new ElementsZone(this.rail, { only: 'y', remember: true });
    this.railZone.neighbors = { right: () => this.currentTab().defaultZone(), up: () => null, down: () => null };
    this.select('home', false);
  }

  private railItem(iconName: string, label: string, onClick: () => void): HTMLElement {
    const el = h('div', { class: 'rail-item s105', 'data-focus': true }, icon(iconName, 48), h('div', { class: 'label' }, label));
    el.addEventListener('click', onClick);
    return el;
  }

  private create(id: TabId): Tab {
    switch (id) {
      case 'home': return homeTab((t) => this.select(t, true));
      case 'live': return liveTab();
      case 'movies': return vodTab('MOVIE');
      case 'series': return vodTab('SERIES');
    }
  }

  currentTab(): Tab {
    let t = this.tabs.get(this.selected);
    if (!t) {
      t = this.create(this.selected);
      this.tabs.set(this.selected, t);
      t.el.classList.add('tab');
      this.content.appendChild(t.el);
    }
    return t;
  }

  select(id: TabId, focusContent: boolean): void {
    const prev = this.tabs.get(this.selected);
    if (prev && id !== this.selected) { prev.el.style.display = 'none'; prev.onHide?.(); }
    this.selected = id;
    this.items.forEach((el, key) => el.classList.toggle('selected', key === id));
    const tab = this.currentTab();
    tab.el.style.display = '';
    this.scope.zones = [this.railZone, ...tab.zones];
    tab.onShow?.();
    if (focusContent || !this.scope.current) {
      const z = tab.defaultZone();
      if (!z || !this.scope.focus(z)) { this.scope.focus(this.railZone); this.pendingContentFocus = true; }
      else this.pendingContentFocus = false;
    }
  }

  /** Tabs call this when their zones change (data loaded, category switched). */
  refreshZones(): void {
    const tab = this.currentTab();
    const focusedInRail = this.scope.current === this.railZone;
    this.scope.zones = [this.railZone, ...tab.zones];
    if (focusedInRail && this.pendingContentFocus) {
      const z = tab.defaultZone();
      if (z && this.scope.focus(z)) this.pendingContentFocus = false;
      return;
    }
    if (!focusedInRail && (!this.scope.current || this.scope.zones.indexOf(this.scope.current) < 0 || !this.scope.current.canFocus())) {
      const z = tab.defaultZone();
      if (!z || !this.scope.focus(z)) this.scope.focus(this.railZone);
    }
  }

  onKey(k: KeyPress): boolean {
    if (this.scope.current === this.railZone) this.pendingContentFocus = false; // the user is using the rail
    if (this.scope.current !== this.railZone) {
      const tab = this.currentTab();
      if (tab.onKey && tab.onKey(k)) return true;
    }
    return false;
  }

  onBack(): boolean {
    if (this.scope.current !== this.railZone) {
      const tab = this.currentTab();
      if (tab.onBack && tab.onBack()) return true;
    }
    // Samsung QA: Return on the main page goes straight back to Smart Hub (no confirmation pop-up).
    if (this.selected === 'home') { exitApp(); return true; }
    if (this.scope.current !== this.railZone) {
      // Back from the content goes to the rail (TV convention), on the selected tab.
      const item = this.items.get(this.selected);
      this.scope.focus(this.railZone);
      if (item) this.railZone.focusElement(item);
      return true;
    }
    this.select('home', false);
    const item = this.items.get('home');
    if (item) this.railZone.focusElement(item);
    return true;
  }

  onShow(): void {
    this.currentTab().onShow?.();
  }

  onHide(): void {
    this.currentTab().onHide?.();
  }

  destroy(): void {
    this.tabs.forEach((t) => t.destroy?.());
  }
}

let shellRef: Shell | null = null;
export function setShell(s: Shell | null): void { shellRef = s; }
export function shell(): Shell | null { return shellRef; }

export function openShell(): void {
  const s = new Shell();
  setShell(s);
  app().reset(s);
}

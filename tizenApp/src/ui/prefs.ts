// Small UI preferences (disclaimer accepted, aspect mode...). Data and settings live in the core (IndexedDB).
const PREFIX = 'steplayer.';

export const prefs = {
  get(key: string): string | null {
    try { return window.localStorage.getItem(PREFIX + key); } catch { return null; }
  },
  set(key: string, value: string): void {
    try { window.localStorage.setItem(PREFIX + key, value); } catch { /* storage full or disabled */ }
  },
};

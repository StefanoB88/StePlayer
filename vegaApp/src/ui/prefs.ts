// Small UI preferences (terms accepted...) in the app's persistent /data folder. Lists, catalog and settings live in
// the shared core.
import { KeplerFileSystem as FileSystem } from '@amazon-devices/kepler-file-system';

const FILE = '/data/ui-prefs.json';
let cache: Record<string, string> | null = null;

export async function loadPrefs(): Promise<void> {
  try { cache = JSON.parse(await FileSystem.readFileAsString(FILE, 'UTF-8')); } catch { cache = {}; }
}

export const prefs = {
  get(key: string): string | null { return cache ? cache[key] ?? null : null; },
  set(key: string, value: string): void {
    if (!cache) cache = {};
    cache[key] = value;
    FileSystem.writeStringToFile(FILE, JSON.stringify(cache), 'UTF-8').catch(() => undefined);
  },
};

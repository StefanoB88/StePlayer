// Active list (source) shared by every screen.
import type { Source } from '../core/api.ts';
import { app } from './app.ts';

type Listener = () => void;

class Session {
  source: Source | null = null;
  sources: Source[] = [];
  private listeners: Listener[] = [];

  async reload(): Promise<void> {
    const core = app().core;
    const [current, all] = await Promise.all([core.sources.current(), core.sources.all()]);
    const changed = (current ? current.id : -1) !== (this.source ? this.source.id : -1) || all.length !== this.sources.length;
    this.source = current;
    this.sources = all;
    if (changed) this.listeners.forEach((l) => l());
  }

  get sourceId(): number { return this.source ? this.source.id : -1; }

  onChange(l: Listener): () => void {
    this.listeners.push(l);
    return () => { this.listeners = this.listeners.filter((x) => x !== l); };
  }
}

export const session = new Session();

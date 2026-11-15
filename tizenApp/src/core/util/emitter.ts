// Replaces Kotlin Flows: listeners are told "this area changed", the UI re-queries.

export class Emitter<E extends string> {
  private readonly listeners: { [event: string]: Array<() => void> } = {};

  on(event: E, listener: () => void): () => void {
    const list = this.listeners[event] || (this.listeners[event] = []);
    list.push(listener);
    return () => {
      const i = list.indexOf(listener);
      if (i >= 0) list.splice(i, 1);
    };
  }

  emit(event: E): void {
    const list = this.listeners[event];
    if (!list) return;
    for (const l of list.slice()) {
      // A throwing listener must never break the core (Kotlin CFlow.watch logs and continues).
      try { l(); } catch (e) { logCore('listener failed', e); }
    }
  }
}

/** Minimal logging for failures the core swallows on purpose. Never pass URLs or secrets here. */
export function logCore(message: string, error?: unknown): void {
  const detail = error instanceof Error ? ` — ${error.name}: ${error.message}` : '';
  // eslint-disable-next-line no-console
  console.warn(`StePlayerCore: ${message}${detail}`);
}

/** Serialises async sections (Kotlin Mutex.withLock). */
export class Mutex {
  private tail: Promise<unknown> = Promise.resolve();

  withLock<T>(fn: () => Promise<T>): Promise<T> {
    const run = this.tail.then(fn, fn);
    this.tail = run.then(() => undefined, () => undefined);
    return run;
  }
}

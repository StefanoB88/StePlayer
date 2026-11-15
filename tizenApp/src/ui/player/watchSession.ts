// Keeps abandoned players from streaming (and holding the subscription's connection) all night.
// Port of the Kotlin playback/WatchSessionTimer.kt.
export type StopReason = 'INACTIVITY' | 'SLEEP_TIMER';
export type SessionState =
  | { kind: 'running' }
  | { kind: 'ask'; secondsLeft: number }
  | { kind: 'sleep'; secondsLeft: number }
  | { kind: 'stop'; reason: StopReason };

export class WatchSessionTimer {
  private readonly inactivityMs: number;
  private readonly promptCountdownMs: number;
  private readonly sleepWarningMs: number;
  private lastInteraction = -1;
  private sleepAt: number | null = null;

  constructor(stillWatchingAfterHours: number, promptCountdownMs = 60_000, sleepWarningMs = 60_000) {
    this.inactivityMs = stillWatchingAfterHours * 3_600_000;
    this.promptCountdownMs = promptCountdownMs;
    this.sleepWarningMs = sleepWarningMs;
  }

  sleepRemainingMs(nowMs: number): number | null { return this.sleepAt == null ? null : Math.max(0, this.sleepAt - nowMs); }
  start(nowMs: number): void { this.lastInteraction = nowMs; }
  onInteraction(nowMs: number): void { this.lastInteraction = nowMs; }
  setSleepTimer(minutes: number, nowMs: number): void { this.sleepAt = nowMs + minutes * 60_000; }
  setSleepAt(epochMs: number): void { this.sleepAt = epochMs; }
  extendSleep(minutes: number, nowMs: number): void { this.sleepAt = Math.max(this.sleepAt ?? nowMs, nowMs) + minutes * 60_000; }
  cancelSleep(): void { this.sleepAt = null; }

  tick(nowMs: number): SessionState {
    if (this.lastInteraction < 0) this.lastInteraction = nowMs;
    const at = this.sleepAt;
    if (at != null) {
      if (nowMs >= at) { this.sleepAt = null; return { kind: 'stop', reason: 'SLEEP_TIMER' }; }
      if (at - nowMs <= this.sleepWarningMs) return { kind: 'sleep', secondsLeft: Math.floor((at - nowMs + 999) / 1000) };
    }
    if (this.inactivityMs > 0) {
      const idle = nowMs - this.lastInteraction;
      if (idle >= this.inactivityMs + this.promptCountdownMs) return { kind: 'stop', reason: 'INACTIVITY' };
      if (idle >= this.inactivityMs) return { kind: 'ask', secondsLeft: Math.floor((this.inactivityMs + this.promptCountdownMs - idle + 999) / 1000) };
    }
    return { kind: 'running' };
  }
}

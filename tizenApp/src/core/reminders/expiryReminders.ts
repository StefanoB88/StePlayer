// Port of reminders/ExpiryReminders.kt — on TV only the in-app banner (no OS notifications).
// Neutral wording on purpose: StePlayer never points to sellers or renewal offers.
import type { ExpiryNotice, Source } from '../api.ts';
import type { Ctx } from '../data/context.ts';
import { S } from '../data/db.ts';
import type { SourceRepository } from '../data/sourceRepository.ts';
import { DAY, nowMillis } from '../util/time.ts';

const KEY_ENABLED = 'expiry_reminders_enabled';

/** Banners for the home screen: lists expiring within 3 days or already expired. */
export function noticesFor(list: Source[], now: number): ExpiryNotice[] {
  const out: ExpiryNotice[] = [];
  for (const s of list) {
    const exp = s.expiresAt;
    if (exp == null) continue;
    const left = exp - now;
    if (left > 3 * DAY) continue;
    const days = left <= 0 ? 0 : Math.trunc((left + DAY - 1) / DAY);
    let message: string;
    if (left <= 0) message = `L'abbonamento «${s.name}» è scaduto.`;
    else if (days <= 1) message = `L'abbonamento «${s.name}» scade entro 24 ore.`;
    else message = `L'abbonamento «${s.name}» scade tra ${days} giorni.`;
    out.push({ sourceId: s.id, sourceName: s.name, expiresAtMs: exp, daysLeft: days, expired: left <= 0, message });
  }
  return out;
}

export class ExpiryReminders {
  private readonly ctx: Ctx;
  private readonly sources: SourceRepository;

  constructor(ctx: Ctx, sources: SourceRepository) {
    this.ctx = ctx;
    this.sources = sources;
  }

  async enabled(): Promise<boolean> {
    return (await this.ctx.db.get<string>(S.settings, KEY_ENABLED)) !== '0';
  }

  async setEnabled(value: boolean): Promise<void> {
    await this.ctx.db.put(S.settings, value ? '1' : '0', KEY_ENABLED);
    this.ctx.events.emit('settings');
  }

  async notice(sourceId: number, nowMs: number = nowMillis()): Promise<ExpiryNotice | null> {
    if (!(await this.enabled())) return null;
    const source = await this.sources.byId(sourceId);
    if (!source) return null;
    return noticesFor([source], nowMs)[0] ?? null;
  }
}

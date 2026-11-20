// Development diagnostics to a log server on the Mac (the Vega Virtual Device reaches it at 10.0.2.2). Release
// builds keep this null: nothing leaves the device.
export const DEV_LOG_URL: string | null = null; // development: 'http://10.0.2.2:8099'

export function devLog(message: string): void {
  if (!DEV_LOG_URL) return;
  fetch(`${DEV_LOG_URL}/log`, { method: 'POST', body: message }).catch(() => undefined);
}

/** Development: with no list yet, add the public demo list automatically (skips typing on the virtual device). */
export const DEV_AUTOLIST: string | null = DEV_LOG_URL ? 'https://stefanob88.github.io/steplayerwebsite/demo.m3u' : null;

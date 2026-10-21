/** A time as the viewer's clock shows it; empty for a missing one. */
export function time(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/** "3 min ago", relative to `now` (a parameter so a test can fix it). */
export function ago(iso: string | null | undefined, now: number = Date.now()): string {
  if (!iso) return 'never';
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000));
  if (Number.isNaN(seconds)) return '—';
  if (seconds < 60) return `${seconds} s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86400)} d ago`;
}

export function ms(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `${value} ms`;
}

/** A datetime-local value ("2026-10-19T14:30") to an ISO instant, or undefined when empty or invalid. */
export function localToInstant(value: string): string | undefined {
  if (!value) return undefined;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

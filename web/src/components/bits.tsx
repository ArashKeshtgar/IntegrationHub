import type { ReactNode } from 'react';

/** A status as text and a mark, never colour alone. */
export function StatusBadge({ status }: { status: string }) {
  const marks: Record<string, string> = { ok: '●', idle: '○', degraded: '▲', down: '■' };
  return (
    <span className={`badge ${status}`}>
      <span aria-hidden="true">{marks[status] ?? '•'}</span> {status}
    </span>
  );
}

/** An HTTP status, marked when it is a failure the hub answered. */
export function HttpStatus({ status }: { status: number | null | undefined }) {
  if (status === null || status === undefined) return <span className="muted">—</span>;
  const kind = status >= 500 ? 'bad' : status >= 400 ? 'warn' : 'good';
  return <span className={`http ${kind}`}>{status}</span>;
}

export function Tile({ label, value, hint, tone }: { label: string; value: ReactNode; hint?: string; tone?: 'critical' }) {
  return (
    <div className={`tile ${tone ?? ''}`}>
      <div className="tile-label">{label}</div>
      <div className="tile-value">{value}</div>
      {hint && <div className="tile-hint">{hint}</div>}
    </div>
  );
}

export function Problem({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="problem" role="alert">
      <span>{message}</span>
      {onRetry && (
        <button type="button" className="btn" onClick={onRetry}>
          Try again
        </button>
      )}
    </div>
  );
}

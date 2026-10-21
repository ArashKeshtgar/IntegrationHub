import { useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { useResource } from '../api/useResource';
import type { Alert } from '../api/types';
import { Problem } from '../components/bits';
import { time } from '../format';

const KINDS: Record<string, string> = {
  'route-down': 'Route down',
  'hl7-dead-letters': 'HL7 dead letters',
};

/** Open alerts, or all of them with when they cleared. */
export function Alerts() {
  const auth = useAuth();
  const [state, setState] = useState<'open' | 'all'>('open');
  const alerts = useResource<{ items: Alert[] }>(auth, '/api/admin/alerts', { state, limit: '100' }, 20_000);
  if (alerts.error && !alerts.data) return <Problem message={alerts.error} onRetry={alerts.reload} />;
  const items = alerts.data?.items ?? [];
  return (
    <>
      <h1>Alerts</h1>
      <div className="segmented" role="group" aria-label="Which alerts">
        <button type="button" className={state === 'open' ? 'on' : ''} aria-pressed={state === 'open'} onClick={() => setState('open')}>
          Open
        </button>
        <button type="button" className={state === 'all' ? 'on' : ''} aria-pressed={state === 'all'} onClick={() => setState('all')}>
          All
        </button>
      </div>
      <table>
        <thead>
          <tr>
            <th>Opened</th>
            <th>Kind</th>
            <th>Subject</th>
            <th>What</th>
            <th>Cleared</th>
            <th>Told</th>
          </tr>
        </thead>
        <tbody>
          {items.map((a) => (
            <tr key={a.id}>
              <td>{time(a.openedAt)}</td>
              <td>{KINDS[a.kind] ?? a.kind}</td>
              <td>{a.subject}</td>
              <td>{a.detail}</td>
              <td>{a.resolvedAt ? time(a.resolvedAt) : <strong>open</strong>}</td>
              <td>{a.notified ? 'yes' : 'not yet'}</td>
            </tr>
          ))}
          {items.length === 0 && (
            <tr>
              <td colSpan={6} className="muted">
                {state === 'open' ? 'No open alerts.' : 'No alerts yet.'}
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <p className="muted small">
        An alert is also written to the hub's log and, when a webhook is set, sent to it. E-mail is not built: let the webhook send it.
      </p>
    </>
  );
}

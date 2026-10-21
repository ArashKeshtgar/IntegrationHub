import { useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { apiPost, ApiError } from '../api/client';
import { useResource } from '../api/useResource';
import type { Letter, Me } from '../api/types';
import { Problem } from '../components/bits';
import { time } from '../format';

interface Data {
  items: Letter[];
  replayWindowDays: number;
}

/**
 * HL7 messages the hub gave up on or ClinicHub refused. Replay puts one back in its queue; the hub
 * retries it within seconds. The message itself is never shown, only queue facts.
 */
export function DeadLetters({ me }: { me: Me }) {
  const auth = useAuth();
  const letters = useResource<Data>(auth, '/api/admin/dead-letters', {}, 20_000);
  const [confirming, setConfirming] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'good' | 'bad'; text: string } | null>(null);

  async function replay(letter: Letter) {
    setBusy(true);
    try {
      await apiPost(auth, `/api/admin/dead-letters/${letter.queue}/${letter.id}/replay`);
      setNotice({ tone: 'good', text: 'Queued. The hub will retry it within a few seconds.' });
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof ApiError ? e.message : 'The hub could not be reached.' });
    } finally {
      setBusy(false);
      setConfirming(null);
      letters.reload();
    }
  }

  if (letters.error && !letters.data) return <Problem message={letters.error} onRetry={letters.reload} />;
  if (!letters.data) return <p>Loading…</p>;
  const { items, replayWindowDays } = letters.data;
  return (
    <>
      <h1>Dead letters</h1>
      <p className="muted">
        HL7 messages the hub gave up on after repeated tries, or that ClinicHub refused. A message can be replayed for{' '}
        {replayWindowDays} days after it failed; then its content is dropped and only this row remains.
        {!me.hl7Enabled && ' HL7 is switched off on this hub, so nothing can be replayed.'}
      </p>
      {notice && (
        <div className={`notice ${notice.tone}`} role="status">
          {notice.text}
        </div>
      )}
      <table>
        <thead>
          <tr>
            <th>Failed</th>
            <th>Queue</th>
            <th>Status</th>
            <th>Sender / control id</th>
            <th className="num">Tries</th>
            <th>Last reason</th>
            <th>Replays</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {items.map((l) => (
            <tr key={l.id}>
              <td>{time(l.failedAt ?? l.receivedAt)}</td>
              <td>{l.queue}</td>
              <td>{l.status.toLowerCase()}</td>
              <td className="mono small">{l.queue === 'inbound' ? `${l.sender} · ${l.controlId}` : 'copy to the receiver'}</td>
              <td className="num">{l.attempts}</td>
              <td>
                {l.lastError ?? '—'}
                {l.lastStatus ? ` (${l.lastStatus})` : ''}
              </td>
              <td className="small">{l.replayCount > 0 ? `${l.replayCount}, last ${time(l.lastReplayedAt)}` : '—'}</td>
              <td>
                {!l.replayable && <span className="muted small">No longer kept</span>}
                {l.replayable && !me.canReplay && <span className="muted small">No permission to replay</span>}
                {l.replayable &&
                  me.canReplay &&
                  (confirming === l.id ? (
                    <span className="confirm">
                      Replay this message?{' '}
                      <button type="button" className="btn primary" disabled={busy} onClick={() => void replay(l)}>
                        Yes, replay
                      </button>{' '}
                      <button type="button" className="btn" disabled={busy} onClick={() => setConfirming(null)}>
                        No
                      </button>
                    </span>
                  ) : (
                    <button type="button" className="btn" onClick={() => setConfirming(l.id)}>
                      Replay
                    </button>
                  ))}
              </td>
            </tr>
          ))}
          {items.length === 0 && (
            <tr>
              <td colSpan={8} className="muted">
                Nothing is waiting for attention.
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </>
  );
}

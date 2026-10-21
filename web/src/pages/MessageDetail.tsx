import { Link, useParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useResource } from '../api/useResource';
import type { Message, Timeline } from '../api/types';
import { HttpStatus, Problem } from '../components/bits';
import { ms, time } from '../format';

/** One call: what the hub recorded, what happened to it, and the other calls about the same identifier. */
export function MessageDetail() {
  const auth = useAuth();
  const { id } = useParams();
  const message = useResource<Message>(auth, id ? `/api/messages/${id}` : null);
  const timeline = useResource<Timeline>(auth, id ? `/api/messages/${id}/timeline` : null);
  if (message.error) return <Problem message={message.error} onRetry={message.reload} />;
  const m = message.data;
  if (!m) return <p>Loading…</p>;
  return (
    <>
      <p>
        <Link to="/messages">← Messages</Link>
      </p>
      <h1>
        {m.method} {m.path}
      </h1>
      <dl className="facts">
        <dt>Received</dt>
        <dd>{time(m.receivedAt)}</dd>
        <dt>Route</dt>
        <dd>{m.route}</dd>
        <dt>From</dt>
        <dd>{m.senderClientId ?? '—'}</dd>
        <dt>Clinic</dt>
        <dd>{m.clinicSlug ?? '—'}</dd>
        <dt>Upstream answered</dt>
        <dd>
          <HttpStatus status={m.upstreamStatus} />
        </dd>
        <dt>Hub answered</dt>
        <dd>
          <HttpStatus status={m.responseStatus} /> in {ms(m.latencyMs)}
        </dd>
        {m.error && (
          <>
            <dt>Note</dt>
            <dd>{m.error}</dd>
          </>
        )}
        <dt>Size</dt>
        <dd>{m.requestSize === null ? '—' : `${m.requestSize} bytes`}</dd>
        <dt>SHA-256</dt>
        <dd className="mono small">{m.requestSha256 ?? '—'}</dd>
        <dt>Identifiers</dt>
        <dd>
          {m.identifiers.length === 0 && '—'}
          {m.identifiers.map((i) => (
            <div key={`${i.system}|${i.value}`} className="mono small">
              <Link to={`/messages?identifier=${encodeURIComponent(`${i.system}|${i.value}`)}`}>
                {i.system}|{i.value}
              </Link>
            </div>
          ))}
        </dd>
      </dl>
      <p className="muted small">The hub keeps no message bodies: only these facts and a fingerprint.</p>

      <h2>What happened</h2>
      {timeline.error && <Problem message={timeline.error} onRetry={timeline.reload} />}
      <ol className="timeline">
        {(timeline.data?.steps ?? []).map((s, i) => (
          <li key={`${s.at}-${i}`} className={s.kind}>
            <span className="when">{time(s.at)}</span> <span>{s.text}</span>
          </li>
        ))}
      </ol>

      {(timeline.data?.related.length ?? 0) > 0 && (
        <>
          <h2>Other calls with the same identifier</h2>
          <ul className="related">
            {timeline.data?.related.map((r) => (
              <li key={r.id}>
                <Link to={`/messages/${r.id}`}>{time(r.receivedAt)}</Link> {r.method} on {r.route} · <HttpStatus status={r.responseStatus} />
              </li>
            ))}
          </ul>
        </>
      )}
    </>
  );
}

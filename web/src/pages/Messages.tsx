import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useResource } from '../api/useResource';
import type { Message, Overview } from '../api/types';
import { HttpStatus, Problem } from '../components/bits';
import { localToInstant, ms, time } from '../format';

const PAGE = 50;

interface Page {
  items: Message[];
  limit: number;
  offset: number;
}

/** Search of the message log. Metadata only: what the hub saw and answered, never a body. */
export function Messages() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [search, setSearch] = useSearchParams();
  const overview = useResource<Overview>(auth, '/api/admin/overview');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const offset = Math.max(0, Number(search.get('offset') ?? '0') || 0);
  const params = {
    route: search.get('route') ?? undefined,
    clinic: search.get('clinic') ?? undefined,
    status: search.get('status') ?? undefined,
    identifier: search.get('identifier') ?? undefined,
    from: search.get('from') ?? undefined,
    to: search.get('to') ?? undefined,
    limit: String(PAGE),
    offset: String(offset),
  };
  const page = useResource<Page>(auth, '/api/messages', params);

  function submit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    const data = new FormData(e.currentTarget);
    const next = new URLSearchParams();
    for (const name of ['route', 'clinic', 'status', 'identifier']) {
      const value = String(data.get(name) ?? '').trim();
      if (value) next.set(name, value);
    }
    const fromIso = localToInstant(from);
    const toIso = localToInstant(to);
    if (fromIso) next.set('from', fromIso);
    if (toIso) next.set('to', toIso);
    setSearch(next);
  }

  function move(delta: number) {
    const next = new URLSearchParams(search);
    const value = Math.max(0, offset + delta);
    if (value === 0) next.delete('offset');
    else next.set('offset', String(value));
    setSearch(next);
  }

  const items = page.data?.items ?? [];
  return (
    <>
      <h1>Messages</h1>
      <form className="filters" onSubmit={submit}>
        <label>
          Route
          <select name="route" defaultValue={search.get('route') ?? ''}>
            <option value="">All</option>
            {(overview.data?.routes ?? []).map((r) => (
              <option key={r.route} value={r.route}>
                {r.route}
              </option>
            ))}
          </select>
        </label>
        <label>
          Clinic
          <input name="clinic" defaultValue={search.get('clinic') ?? ''} placeholder="harbourview" />
        </label>
        <label>
          Answered
          <input name="status" inputMode="numeric" defaultValue={search.get('status') ?? ''} placeholder="200" size={5} />
        </label>
        <label>
          Identifier
          <input name="identifier" defaultValue={search.get('identifier') ?? ''} placeholder="42 or system|42" />
        </label>
        <label>
          From
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          To
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <button type="submit" className="btn primary">
          Search
        </button>
      </form>

      {page.error && <Problem message={page.error} onRetry={page.reload} />}
      <table>
        <thead>
          <tr>
            <th>Received</th>
            <th>Route</th>
            <th>From</th>
            <th>Clinic</th>
            <th>Call</th>
            <th>Upstream</th>
            <th>Answered</th>
            <th className="num">Time</th>
          </tr>
        </thead>
        <tbody>
          {items.map((m) => (
            <tr key={m.id} className="row-link" onClick={() => navigate(`/messages/${m.id}`)}>
              <td>
                <Link to={`/messages/${m.id}`}>{time(m.receivedAt)}</Link>
              </td>
              <td>{m.route}</td>
              <td>{m.senderClientId ?? '—'}</td>
              <td>{m.clinicSlug ?? '—'}</td>
              <td className="mono">
                {m.method} {m.path}
              </td>
              <td>
                <HttpStatus status={m.upstreamStatus} />
              </td>
              <td>
                <HttpStatus status={m.responseStatus} />
              </td>
              <td className="num">{ms(m.latencyMs)}</td>
            </tr>
          ))}
          {!page.loading && items.length === 0 && !page.error && (
            <tr>
              <td colSpan={8} className="muted">
                No messages match.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      <div className="pager">
        <button type="button" className="btn" disabled={offset === 0} onClick={() => move(-PAGE)}>
          Newer
        </button>
        <span className="muted">
          {items.length === 0 ? 0 : offset + 1}–{offset + items.length}
        </span>
        <button type="button" className="btn" disabled={items.length < PAGE} onClick={() => move(PAGE)}>
          Older
        </button>
      </div>
    </>
  );
}

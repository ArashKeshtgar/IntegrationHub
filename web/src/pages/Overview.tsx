import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useResource } from '../api/useResource';
import type { Overview as OverviewData } from '../api/types';
import { Problem, StatusBadge, Tile } from '../components/bits';
import { ago, ms, time } from '../format';

/** Route health, what is waiting in the queues, and whether anything needs attention. */
export function Overview() {
  const auth = useAuth();
  const overview = useResource<OverviewData>(auth, '/api/admin/overview', {}, 15_000);
  if (overview.error && !overview.data) return <Problem message={overview.error} onRetry={overview.reload} />;
  if (!overview.data) return <p>Loading…</p>;
  const { routes, queues, openAlerts } = overview.data;
  const deadLetters = queues.hl7InboundFailed + queues.hl7InboundRejected + queues.hl7OutboundFailed;
  return (
    <>
      <h1>Overview</h1>
      <div className="tiles">
        <Tile label="Open alerts" value={openAlerts} tone={openAlerts > 0 ? 'critical' : undefined} hint="see Alerts" />
        <Tile label="Dead letters" value={deadLetters} tone={deadLetters > 0 ? 'critical' : undefined} hint="HL7 given up or refused" />
        <Tile label="HL7 waiting" value={queues.hl7InboundPending + queues.hl7OutboundPending} hint="in, out" />
        <Tile label="Events waiting" value={queues.eventsWaiting} hint="for the broker" />
      </div>

      <h2>Routes</h2>
      <table>
        <thead>
          <tr>
            <th>Route</th>
            <th>Status</th>
            <th className="num">Calls, 15 min</th>
            <th className="num">Failed, 15 min</th>
            <th className="num">p95</th>
            <th className="num">Calls, 24 h</th>
            <th>Last answered</th>
            <th>Last failure</th>
          </tr>
        </thead>
        <tbody>
          {routes.map((r) => (
            <tr key={r.route}>
              <td>
                <Link to={`/messages?route=${encodeURIComponent(r.route)}`}>{r.route}</Link>
              </td>
              <td>
                <StatusBadge status={r.status} />
              </td>
              <td className="num">{r.calls15m}</td>
              <td className="num">{r.failures15m}</td>
              <td className="num">{ms(r.p95Ms)}</td>
              <td className="num">{r.calls24h}</td>
              <td title={time(r.lastSuccessAt)}>{ago(r.lastSuccessAt)}</td>
              <td title={time(r.lastFailureAt)}>{r.lastFailureAt ? ago(r.lastFailureAt) : 'none in 24 h'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small">
        A call failed when the hub answered 5xx. A route with no calls is idle: nothing is probing the applications.
        <br />
        Updated {time(overview.data.generatedAt)}.
      </p>
    </>
  );
}

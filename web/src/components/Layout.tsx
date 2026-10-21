import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { useResource } from '../api/useResource';
import type { Overview } from '../api/types';

/** The shell: title, navigation with counts that need attention, who is signed in. */
export function Layout() {
  const auth = useAuth();
  const overview = useResource<Overview>(auth, '/api/admin/overview', {}, 30_000);
  const queues = overview.data?.queues;
  const deadLetters = queues ? queues.hl7InboundFailed + queues.hl7InboundRejected + queues.hl7OutboundFailed : 0;
  const alerts = overview.data?.openAlerts ?? 0;
  return (
    <>
      <header className="top">
        <div className="brand">IntegrationHub</div>
        <nav aria-label="Main">
          <NavLink to="/" end>
            Overview
          </NavLink>
          <NavLink to="/messages">Messages</NavLink>
          <NavLink to="/dead-letters">
            Dead letters
            {deadLetters > 0 && <span className="count">{deadLetters}</span>}
          </NavLink>
          <NavLink to="/code-map">Lab codes</NavLink>
          <NavLink to="/alerts">
            Alerts
            {alerts > 0 && <span className="count critical">{alerts}</span>}
          </NavLink>
        </nav>
        <div className="who">
          {auth.name && <span>{auth.name}</span>}
          <button type="button" className="btn quiet" onClick={() => auth.signOut()}>
            Sign out
          </button>
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </>
  );
}

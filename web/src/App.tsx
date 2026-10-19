import type { ReactNode } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth/AuthProvider';
import { useResource } from './api/useResource';
import type { Me } from './api/types';
import { Layout } from './components/Layout';
import { Alerts } from './pages/Alerts';
import { DeadLetters } from './pages/DeadLetters';
import { MessageDetail } from './pages/MessageDetail';
import { Messages } from './pages/Messages';
import { Overview } from './pages/Overview';

function Center({ children }: { children: ReactNode }) {
  return <div className="center">{children}</div>;
}

/** Signed in at ClinicHub: ask the hub who this is and what they may do. */
function Signed() {
  const auth = useAuth();
  const me = useResource<Me>(auth, '/api/admin/me');
  if (me.error) {
    return (
      <Center>
        <p className="error">{me.error}</p>
        <p className="muted">Your role needs the IntegrationHub permission “See the message log” (ask an administrator).</p>
        <button type="button" className="btn" onClick={() => auth.signOut()}>
          Sign out
        </button>
      </Center>
    );
  }
  if (!me.data) {
    return (
      <Center>
        <p>Loading…</p>
      </Center>
    );
  }
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<Overview />} />
        <Route path="messages" element={<Messages />} />
        <Route path="messages/:id" element={<MessageDetail />} />
        <Route path="dead-letters" element={<DeadLetters me={me.data} />} />
        <Route path="alerts" element={<Alerts />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  const auth = useAuth();
  switch (auth.status) {
    case 'loading':
    case 'redirecting':
      return (
        <Center>
          <p>Signing in…</p>
        </Center>
      );
    case 'error':
      return (
        <Center>
          <p className="error">{auth.error}</p>
          <button type="button" className="btn" onClick={() => auth.signIn('/')}>
            Try again
          </button>
        </Center>
      );
    case 'signed-out':
      return (
        <Center>
          <h1>Signed out</h1>
          <button type="button" className="btn" onClick={() => auth.signIn('/')}>
            Sign in
          </button>
        </Center>
      );
    case 'signed-in':
      return <Signed />;
  }
}

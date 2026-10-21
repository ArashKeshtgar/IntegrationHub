import { type User, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { useNavigate } from 'react-router-dom';
import { createContext, type ReactNode, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { Session } from '../api/client';
import { config } from '../config';

export const CALLBACK_PATH = '/signin-oidc';

type Status = 'loading' | 'signed-in' | 'redirecting' | 'signed-out' | 'error';

interface AuthValue extends Session {
  status: Status;
  error: string | null;
  /** The OIDC `name` claim, for the header; the API's /api/me is the authority on permissions. */
  name: string | null;
  signIn(returnTo?: string): void;
  signOut(): void;
}

const AuthContext = createContext<AuthValue | null>(null);

export function useAuth(): AuthValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth outside AuthProvider');
  return value;
}

function createManager(): UserManager {
  return new UserManager({
    authority: config.authority,
    client_id: config.clientId,
    redirect_uri: `${window.location.origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: `${window.location.origin}/`,
    response_type: 'code', // authorization code with PKCE (S256): oidc-client-ts adds the challenge
    scope: config.scope,
    loadUserInfo: false,
    automaticSilentRenew: false,
    // The token lives for the tab only.
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
}

const LOOP_KEY = 'integrationhub.signin-attempts';

/** Stops a sign-in loop (the IdP keeps sending us back with a token the API refuses). */
function tooManySignIns(): boolean {
  try {
    const now = Date.now();
    const recent: number[] = (JSON.parse(window.sessionStorage.getItem(LOOP_KEY) ?? '[]') as number[]).filter(
      (t) => now - t < 30_000,
    );
    recent.push(now);
    window.sessionStorage.setItem(LOOP_KEY, JSON.stringify(recent));
    return recent.length > 3;
  } catch {
    return false;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const manager = useMemo(createManager, []);
  const navigate = useNavigate();
  const [user, setUser] = useState<User | null>(null);
  const [status, setStatus] = useState<Status>('loading');
  const [error, setError] = useState<string | null>(null);
  const userRef = useRef<User | null>(null);
  userRef.current = user;

  const signIn = useCallback(
    (returnTo?: string) => {
      if (tooManySignIns()) {
        setError('Signing in keeps failing. Check that your account has access to IntegrationHub.');
        setStatus('error');
        return;
      }
      setStatus('redirecting');
      const target = returnTo ?? `${window.location.pathname}${window.location.search}`;
      manager.signinRedirect({ state: { returnTo: target } }).catch((e: unknown) => {
        setError(e instanceof Error ? e.message : 'Could not reach the sign-in service.');
        setStatus('error');
      });
    },
    [manager],
  );

  useEffect(() => {
    let cancelled = false;
    async function start() {
      try {
        if (window.location.pathname === CALLBACK_PATH) {
          const signedIn = await manager.signinRedirectCallback();
          if (cancelled) return;
          const state = signedIn.state as { returnTo?: string } | undefined;
          setUser(signedIn);
          setStatus('signed-in');
          navigate(state?.returnTo ?? '/', { replace: true });
          return;
        }
        const existing = await manager.getUser();
        if (cancelled) return;
        if (existing && !existing.expired) {
          setUser(existing);
          setStatus('signed-in');
        } else {
          signIn();
        }
      } catch (e) {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : 'Sign-in failed.');
        setStatus('error');
      }
    }
    void start();
    return () => {
      cancelled = true;
    };
  }, [manager, signIn, navigate]);

  const value = useMemo<AuthValue>(
    () => ({
      status,
      error,
      name: (user?.profile?.name as string | undefined) ?? null,
      accessToken: async () => {
        const current = userRef.current;
        return current && !current.expired ? current.access_token : null;
      },
      expired: () => {
        void manager.removeUser();
        signIn();
      },
      signIn,
      signOut: () => {
        void manager.removeUser().then(() => {
          setUser(null);
          setStatus('signed-out');
          navigate('/', { replace: true });
        });
      },
    }),
    [status, error, user, manager, signIn, navigate],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

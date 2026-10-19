import { render } from '@testing-library/react';
import { vi } from 'vitest';
import type { ReactElement } from 'react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

/** The session the pages get from useAuth in tests: a token, and spies for sign-in and sign-out. */
export const fakeAuth = {
  status: 'signed-in' as const,
  error: null,
  name: 'Dr. Test',
  accessToken: async () => 'tok',
  expired: vi.fn(),
  signIn: vi.fn(),
  signOut: vi.fn(),
};

/** Stubs fetch: `routes` maps "METHOD /path" (no query) to the JSON answer, or to [status, body]. Returns the calls made. */
export function stubHub(routes: Record<string, unknown>) {
  const calls: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(input);
      const key = `${init?.method ?? 'GET'} ${url.pathname}`;
      calls.push(`${key}${url.search}`);
      const answer = routes[key];
      if (answer === undefined) return new Response(JSON.stringify({ error: `no stub for ${key}` }), { status: 404 });
      if (Array.isArray(answer) && typeof answer[0] === 'number') return new Response(JSON.stringify(answer[1]), { status: answer[0] });
      return new Response(JSON.stringify(answer), { status: 200 });
    }),
  );
  return calls;
}

export function renderAt(path: string, element: ReactElement, pattern = '*') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path={pattern} element={element} />
      </Routes>
    </MemoryRouter>,
  );
}

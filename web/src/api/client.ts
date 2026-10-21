import { config } from '../config';

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export type Params = Record<string, string | undefined>;

/** Something the client can call to get a token and to start over when the API refuses it. */
export interface Session {
  accessToken(): Promise<string | null>;
  /** The API said 401: the token is gone or expired. */
  expired(): void;
}

function messageFor(status: number, detail: string | undefined): string {
  if (status === 403) return detail ?? 'You do not have permission to do this.';
  if (status === 503) return detail ?? 'The hub cannot verify tokens right now.';
  return detail ?? `The request failed (${status}).`;
}

export function buildUrl(path: string, params: Params = {}): string {
  const query = new URLSearchParams();
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') query.set(name, value);
  }
  const text = query.toString();
  return `${config.apiUrl}${path}${text ? `?${text}` : ''}`;
}

async function call<T>(
  session: Session,
  method: 'GET' | 'POST' | 'PUT',
  path: string,
  params: Params,
  signal?: AbortSignal,
  body?: unknown,
): Promise<T> {
  const token = await session.accessToken();
  if (!token) {
    session.expired();
    throw new ApiError(401, 'Sign in again.');
  }
  const response = await fetch(buildUrl(path, params), {
    method,
    headers: {
      Authorization: `Bearer ${token}`,
      Accept: 'application/json',
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    signal,
  });
  if (response.status === 401) {
    session.expired();
    throw new ApiError(401, 'Your session ended. Signing in again.');
  }
  if (!response.ok) {
    let detail: string | undefined;
    try {
      const body = (await response.json()) as { error?: unknown };
      detail = typeof body.error === 'string' ? body.error : undefined;
    } catch {
      detail = undefined;
    }
    throw new ApiError(response.status, messageFor(response.status, detail));
  }
  return (await response.json()) as T;
}

export function apiGet<T>(session: Session, path: string, params: Params = {}, signal?: AbortSignal): Promise<T> {
  return call<T>(session, 'GET', path, params, signal);
}

export function apiPost<T>(session: Session, path: string): Promise<T> {
  return call<T>(session, 'POST', path, {});
}

export function apiPut<T>(session: Session, path: string, body: unknown): Promise<T> {
  return call<T>(session, 'PUT', path, {}, undefined, body);
}

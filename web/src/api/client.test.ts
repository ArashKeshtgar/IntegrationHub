import { afterEach, describe, expect, it, type Mock, vi } from 'vitest';
import { ApiError, apiGet, apiPost, buildUrl, type Session } from './client';

function session(token: string | null = 'tok'): Session & { expired: Mock } {
  return { accessToken: async () => token, expired: vi.fn() } as Session & { expired: Mock };
}

function respond(status: number, body: unknown): void {
  vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify(body), { status })));
}

afterEach(() => vi.unstubAllGlobals());

describe('buildUrl', () => {
  it('leaves out empty parameters and encodes the rest', () => {
    expect(buildUrl('/api/x', { a: '1', b: undefined, c: '', d: 'x y&z' })).toBe('http://localhost:5700/api/x?a=1&d=x+y%26z');
    expect(buildUrl('/api/x')).toBe('http://localhost:5700/api/x');
  });
});

describe('apiGet', () => {
  it('sends the access token and returns the JSON', async () => {
    respond(200, { items: [] });
    expect(await apiGet(session('abc'), '/api/messages', { route: 'labflow' })).toEqual({ items: [] });
    const [url, init] = (fetch as Mock).mock.calls[0] as [string, RequestInit];
    expect(url).toBe('http://localhost:5700/api/messages?route=labflow');
    expect(init.method).toBe('GET');
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer abc');
  });

  it('starts sign-in again without calling the hub when there is no token', async () => {
    respond(200, {});
    const s = session(null);
    await expect(apiGet(s, '/api/x')).rejects.toMatchObject({ status: 401 });
    expect(s.expired).toHaveBeenCalledOnce();
    expect(fetch).not.toHaveBeenCalled();
  });

  it('starts sign-in again when the hub says 401', async () => {
    respond(401, { error: 'Sign in.' });
    const s = session();
    await expect(apiGet(s, '/api/x')).rejects.toBeInstanceOf(ApiError);
    expect(s.expired).toHaveBeenCalledOnce();
  });

  it('uses the hub words for a refusal, and says something plain without them', async () => {
    respond(403, { error: 'No permission to read the message log.' });
    await expect(apiGet(session(), '/api/x')).rejects.toMatchObject({ status: 403, message: 'No permission to read the message log.' });
    respond(403, {});
    await expect(apiGet(session(), '/api/x')).rejects.toMatchObject({ message: 'You do not have permission to do this.' });
    respond(500, 'not json');
    await expect(apiGet(session(), '/api/x')).rejects.toMatchObject({ status: 500, message: 'The request failed (500).' });
  });
});

describe('apiPost', () => {
  it('posts with the token and returns the answer', async () => {
    respond(202, { status: 'queued' });
    expect(await apiPost(session('abc'), '/api/admin/dead-letters/inbound/1/replay')).toEqual({ status: 'queued' });
    const [, init] = (fetch as Mock).mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe('POST');
  });

  it('reports a 409 with the hub reason', async () => {
    respond(409, { error: 'The message is no longer kept (the replay window has passed).' });
    await expect(apiPost(session(), '/x')).rejects.toMatchObject({
      status: 409,
      message: 'The message is no longer kept (the replay window has passed).',
    });
  });
});

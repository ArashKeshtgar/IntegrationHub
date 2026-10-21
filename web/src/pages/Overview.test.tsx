import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeAuth, renderAt, stubHub } from '../test/render';
import { Overview } from './Overview';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => fakeAuth }));
afterEach(() => vi.unstubAllGlobals());

const overview = {
  generatedAt: '2026-10-19T14:00:00Z',
  openAlerts: 2,
  queues: { hl7InboundPending: 1, hl7InboundFailed: 2, hl7InboundRejected: 1, hl7OutboundPending: 0, hl7OutboundFailed: 0, eventsWaiting: 5 },
  routes: [
    { route: 'clinichub', status: 'down', calls15m: 9, failures15m: 9, calls24h: 40, failures24h: 12, p95Ms: 31, lastSuccessAt: '2026-10-19T13:00:00Z', lastFailureAt: '2026-10-19T13:59:00Z' },
    { route: 'labflow', status: 'idle', calls15m: 0, failures15m: 0, calls24h: 0, failures24h: 0, p95Ms: null, lastSuccessAt: null, lastFailureAt: null },
  ],
};

describe('Overview', () => {
  it('shows what needs attention and each route with its status in words', async () => {
    stubHub({ 'GET /api/admin/overview': overview });
    renderAt('/', <Overview />);

    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
    expect(screen.getByText('Open alerts').nextSibling).toHaveTextContent('2');
    expect(screen.getByText('Dead letters').nextSibling).toHaveTextContent('3');
    expect(screen.getByText('Events waiting').nextSibling).toHaveTextContent('5');

    const clinichub = screen.getByRole('row', { name: /clinichub/ });
    expect(clinichub).toHaveTextContent('down');
    expect(clinichub).toHaveTextContent('31 ms');
    const labflow = screen.getByRole('row', { name: /labflow/ });
    expect(labflow).toHaveTextContent('idle');
    expect(labflow).toHaveTextContent('none in 24 h');
    expect(screen.getByRole('link', { name: 'clinichub' })).toHaveAttribute('href', '/messages?route=clinichub');
  });

  it('says so when the hub cannot be read', async () => {
    stubHub({ 'GET /api/admin/overview': [403, { error: 'No permission to read the message log.' }] });
    renderAt('/', <Overview />);
    expect(await screen.findByRole('alert')).toHaveTextContent('No permission to read the message log.');
  });
});

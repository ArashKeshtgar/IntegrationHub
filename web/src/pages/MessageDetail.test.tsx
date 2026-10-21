import { screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeAuth, renderAt, stubHub } from '../test/render';
import { MessageDetail } from './MessageDetail';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => fakeAuth }));
afterEach(() => vi.unstubAllGlobals());

const message = {
  id: 'm1',
  receivedAt: '2026-10-19T14:00:00Z',
  route: 'labflow',
  senderClientId: 'clinichub',
  clinicSlug: 'harbourview',
  method: 'POST',
  path: '/fhir',
  contentType: 'application/fhir+json',
  requestSize: 1234,
  requestSha256: 'ab'.repeat(32),
  bundleId: null,
  resourceSummary: null,
  upstreamStatus: 200,
  responseStatus: 200,
  latencyMs: 41,
  error: null,
  identifiers: [{ system: 'urn:clinichub:harbourview:service-request', value: '42' }],
};
const timeline = {
  id: 'm1',
  steps: [
    { at: '2026-10-19T14:00:00Z', kind: 'received', text: 'POST /fhir on route labflow from clinichub' },
    { at: '2026-10-19T14:00:00.041Z', kind: 'forwarded', text: 'The upstream answered 200 after 41 ms.' },
  ],
  related: [{ id: 'm2', receivedAt: '2026-10-19T14:05:00Z', route: 'clinichub', method: 'POST', responseStatus: 200 }],
};

describe('MessageDetail', () => {
  it('shows the facts, the steps and the other calls about the same order', async () => {
    stubHub({ 'GET /api/messages/m1': message, 'GET /api/messages/m1/timeline': timeline });
    renderAt('/messages/m1', <MessageDetail />, '/messages/:id');

    expect(await screen.findByRole('heading', { name: 'POST /fhir' })).toBeInTheDocument();
    expect(await screen.findByText('The upstream answered 200 after 41 ms.')).toBeInTheDocument();
    expect(screen.getByText('POST /fhir on route labflow from clinichub')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'urn:clinichub:harbourview:service-request|42' })).toHaveAttribute(
      'href',
      '/messages?identifier=urn%3Aclinichub%3Aharbourview%3Aservice-request%7C42',
    );
    expect(screen.getByText(/The hub keeps no message bodies/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Oct 19/ })).toHaveAttribute('href', '/messages/m2');
  });

  it('shows the refusal when the message is unknown', async () => {
    stubHub({ 'GET /api/messages/m1': [404, { error: 'Not Found' }], 'GET /api/messages/m1/timeline': [404, { error: 'Not Found' }] });
    renderAt('/messages/m1', <MessageDetail />, '/messages/:id');
    expect(await screen.findByRole('alert')).toHaveTextContent('Not Found');
  });
});

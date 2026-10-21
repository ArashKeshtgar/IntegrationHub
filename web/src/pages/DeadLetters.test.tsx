import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeAuth, renderAt, stubHub } from '../test/render';
import { DeadLetters } from './DeadLetters';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => fakeAuth }));
afterEach(() => vi.unstubAllGlobals());

const letter = {
  id: 'aaaa',
  queue: 'inbound',
  status: 'FAILED',
  sender: 'EXTLAB.LAB1',
  controlId: 'MSG-77',
  receivedAt: '2026-10-19T13:00:00Z',
  failedAt: '2026-10-19T13:05:00Z',
  attempts: 12,
  lastStatus: 503,
  lastError: 'clinichub-unavailable',
  replayable: true,
  replayCount: 0,
  lastReplayedAt: null,
  lastReplayedBy: null,
};
const gone = { ...letter, id: 'bbbb', controlId: 'MSG-12', replayable: false };
const list = { 'GET /api/admin/dead-letters': { items: [letter, gone], replayWindowDays: 7 } };
const operator = { subject: '42', canReplay: true, hl7Enabled: true };

describe('DeadLetters', () => {
  it('replays a message only after a second click, and says it is queued', async () => {
    const calls = stubHub({ ...list, 'POST /api/admin/dead-letters/inbound/aaaa/replay': [202, { status: 'queued' }] });
    renderAt('/', <DeadLetters me={operator} />);
    expect(await screen.findByText('EXTLAB.LAB1 · MSG-77')).toBeInTheDocument();

    const buttons = screen.getAllByRole('button', { name: 'Replay' });
    expect(buttons).toHaveLength(1); // the other row has no content left to replay
    expect(screen.getByText('No longer kept')).toBeInTheDocument();

    await userEvent.click(buttons[0]!);
    expect(calls.some((c) => c.startsWith('POST'))).toBe(false); // nothing sent yet
    await userEvent.click(screen.getByRole('button', { name: 'Yes, replay' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Queued');
    expect(calls).toContain('POST /api/admin/dead-letters/inbound/aaaa/replay');
  });

  it('shows the hub reason when the replay is refused', async () => {
    stubHub({
      ...list,
      'POST /api/admin/dead-letters/inbound/aaaa/replay': [409, { error: 'Only a message that was given up or refused can be replayed.' }],
    });
    renderAt('/', <DeadLetters me={operator} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Replay' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, replay' }));
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('Only a message that was given up or refused can be replayed.'),
    );
  });

  it('offers no replay without the permission, and says why', async () => {
    stubHub(list);
    renderAt('/', <DeadLetters me={{ ...operator, canReplay: false }} />);
    expect(await screen.findByText('EXTLAB.LAB1 · MSG-77')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument();
    expect(screen.getByText('No permission to replay')).toBeInTheDocument();
  });

  it('says plainly when nothing is waiting', async () => {
    stubHub({ 'GET /api/admin/dead-letters': { items: [], replayWindowDays: 7 } });
    renderAt('/', <DeadLetters me={operator} />);
    expect(await screen.findByText('Nothing is waiting for attention.')).toBeInTheDocument();
  });
});

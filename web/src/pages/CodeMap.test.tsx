import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { fakeAuth, renderAt } from '../test/render';
import { CodeMap } from './CodeMap';

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => fakeAuth }));
afterEach(() => vi.unstubAllGlobals());

const operator = { subject: '42', canReplay: true, hl7Enabled: true };
const viewer = { subject: '7', canReplay: false, hl7Enabled: true };
const start = { items: [{ loinc: '718-7', labCode: 'HGB', labDisplay: 'Haemoglobin', updatedAt: '2026-10-21T03:00:00Z' }] };

/** fetch that serves the map and records what is PUT; `refuse` makes the PUT answer 400 with the hub's words. */
function hub(refuse?: string) {
  const puts: unknown[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init?: RequestInit) => {
      const { pathname } = new URL(input);
      if (pathname !== '/api/admin/code-map') return new Response('{}', { status: 404 });
      if (init?.method === 'PUT') {
        puts.push(JSON.parse(String(init.body)));
        return refuse ? new Response(JSON.stringify({ error: refuse }), { status: 400 }) : new Response(JSON.stringify(start), { status: 200 });
      }
      return new Response(JSON.stringify(start), { status: 200 });
    }),
  );
  return puts;
}

describe('CodeMap', () => {
  it('shows the map and saves the whole of it, with a new row, trimmed', async () => {
    const puts = hub();
    renderAt('/', <CodeMap me={operator} />);
    expect(await screen.findByDisplayValue('HGB')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Add a row' }));
    await userEvent.type(screen.getByLabelText('LOINC, row 2'), ' 2345-7 ');
    await userEvent.type(screen.getByLabelText('Lab’s code, row 2'), 'GLU');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Saved');
    expect(puts).toEqual([
      {
        items: [
          { loinc: '718-7', labCode: 'HGB', labDisplay: 'Haemoglobin' },
          { loinc: '2345-7', labCode: 'GLU', labDisplay: null },
        ],
      },
    ]);
  });

  it('names the bad row when the hub refuses, and leaves what was typed', async () => {
    hub('row 2: LOINC 718-7 is mapped twice');
    renderAt('/', <CodeMap me={operator} />);
    await userEvent.click(await screen.findByRole('button', { name: 'Add a row' }));
    await userEvent.type(screen.getByLabelText('LOINC, row 2'), '718-7');
    await userEvent.type(screen.getByLabelText('Lab’s code, row 2'), 'HB2');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('status')).toHaveTextContent('row 2: LOINC 718-7 is mapped twice');
    expect(screen.getByLabelText('Lab’s code, row 2')).toHaveValue('HB2');
  });

  it('is read-only without the replay permission', async () => {
    hub();
    renderAt('/', <CodeMap me={viewer} />);
    expect(await screen.findByDisplayValue('HGB')).toBeDisabled();
    expect(screen.queryByRole('button', { name: 'Save' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Add a row' })).toBeNull();
    expect(screen.getByText(/needs the replay permission/)).toBeInTheDocument();
  });
});

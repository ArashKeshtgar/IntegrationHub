import { useEffect, useState } from 'react';
import { useAuth } from '../auth/AuthProvider';
import { apiPut, ApiError } from '../api/client';
import { useResource } from '../api/useResource';
import type { CodeMapEntry, Me } from '../api/types';
import { Problem } from '../components/bits';

interface Data {
  items: CodeMapEntry[];
}

type Row = { loinc: string; labCode: string; labDisplay: string };

const blank = (): Row => ({ loinc: '', labCode: '', labDisplay: '' });

/**
 * The external lab's own test codes against LOINC. An order goes out with the lab's code first and the LOINC as the
 * alternate; a result the lab codes only its own way comes back with the LOINC added. Saving replaces the whole map, all
 * or nothing: a bad row is named and nothing changes. Changing it needs the same permission as a replay.
 */
export function CodeMap({ me }: { me: Me }) {
  const auth = useAuth();
  const map = useResource<Data>(auth, '/api/admin/code-map');
  const [rows, setRows] = useState<Row[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<{ tone: 'good' | 'bad'; text: string } | null>(null);

  useEffect(() => {
    if (map.data) setRows(map.data.items.map((e) => ({ loinc: e.loinc, labCode: e.labCode, labDisplay: e.labDisplay ?? '' })));
  }, [map.data]);

  if (map.error && !map.data) return <Problem message={map.error} onRetry={map.reload} />;
  if (!rows) return <p>Loading…</p>;

  const edit = (i: number, field: keyof Row, value: string) => {
    setNotice(null);
    setRows(rows.map((r, at) => (at === i ? { ...r, [field]: value } : r)));
  };

  async function save() {
    setBusy(true);
    setNotice(null);
    try {
      await apiPut(auth, '/api/admin/code-map', {
        items: rows!.filter((r) => r.loinc.trim() || r.labCode.trim()).map((r) => ({ loinc: r.loinc.trim(), labCode: r.labCode.trim(), labDisplay: r.labDisplay.trim() || null })),
      });
      setNotice({ tone: 'good', text: 'Saved. The next order and the next result use it.' });
      map.reload();
    } catch (e) {
      setNotice({ tone: 'bad', text: e instanceof ApiError ? e.message : 'The hub could not be reached.' });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <h1>Lab test codes</h1>
      <p className="muted">
        The external lab’s own code for each LOINC test. Orders go out with the lab’s code and the LOINC as the alternate; a result
        coded only the lab’s way comes back with the LOINC added. A test not listed goes as ClinicHub sends it.
        {!me.canReplay && ' You can read the map; changing it needs the replay permission.'}
      </p>
      {notice && (
        <div className={`notice ${notice.tone}`} role="status">
          {notice.text}
        </div>
      )}
      <table>
        <thead>
          <tr>
            <th>LOINC</th>
            <th>Lab’s code</th>
            <th>Lab’s name (optional)</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              <td>
                <input aria-label={`LOINC, row ${i + 1}`} className="mono" value={r.loinc} placeholder="718-7" disabled={!me.canReplay} onChange={(e) => edit(i, 'loinc', e.target.value)} />
              </td>
              <td>
                <input aria-label={`Lab’s code, row ${i + 1}`} className="mono" value={r.labCode} placeholder="HGB" disabled={!me.canReplay} onChange={(e) => edit(i, 'labCode', e.target.value)} />
              </td>
              <td>
                <input aria-label={`Lab’s name, row ${i + 1}`} value={r.labDisplay} disabled={!me.canReplay} onChange={(e) => edit(i, 'labDisplay', e.target.value)} />
              </td>
              <td>
                {me.canReplay && (
                  <button type="button" className="btn" onClick={() => setRows(rows.filter((_, at) => at !== i))}>
                    Remove
                  </button>
                )}
              </td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={4} className="muted">
                No codes yet: every test goes as ClinicHub sends it.
              </td>
            </tr>
          )}
        </tbody>
      </table>
      {me.canReplay && (
        <p>
          <button type="button" className="btn" onClick={() => setRows([...rows, blank()])}>
            Add a row
          </button>{' '}
          <button type="button" className="btn primary" disabled={busy} onClick={() => void save()}>
            Save
          </button>
        </p>
      )}
    </>
  );
}

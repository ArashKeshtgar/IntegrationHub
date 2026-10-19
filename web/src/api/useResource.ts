import { useCallback, useEffect, useRef, useState } from 'react';
import { apiGet, ApiError, type Params, type Session } from './client';

export interface Resource<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  reload(): void;
}

/**
 * Loads `path` with `params`, again when they change, and every `refreshMs` when given (the data is not
 * cleared while it reloads, so a table does not flash).
 */
export function useResource<T>(session: Session, path: string | null, params: Params = {}, refreshMs?: number): Resource<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(path !== null);
  const [tick, setTick] = useState(0);
  const sessionRef = useRef(session);
  sessionRef.current = session;
  const key = JSON.stringify(params);

  useEffect(() => {
    if (path === null) {
      setData(null);
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    apiGet<T>(sessionRef.current, path, JSON.parse(key) as Params, controller.signal)
      .then((result) => {
        setData(result);
        setError(null);
      })
      .catch((e: unknown) => {
        if (controller.signal.aborted) return;
        setError(e instanceof ApiError ? e.message : 'The hub could not be reached.');
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, key, tick]);

  useEffect(() => {
    if (!refreshMs || path === null) return;
    const timer = window.setInterval(() => setTick((n) => n + 1), refreshMs);
    return () => window.clearInterval(timer);
  }, [refreshMs, path]);

  const reload = useCallback(() => setTick((n) => n + 1), []);
  return { data, error, loading, reload };
}

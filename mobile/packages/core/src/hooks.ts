import { DependencyList, useCallback, useEffect, useRef, useState } from 'react';
import { ApiError } from './api';

/** Loads data with loading / error / reload handling. */
export function useQuery<T>(fn: () => Promise<T>, deps: DependencyList = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const alive = useRef(true);

  const run = useCallback(async (isRefresh = false) => {
    isRefresh ? setRefreshing(true) : setLoading(true);
    setError(null);
    try {
      const d = await fn();
      if (alive.current) setData(d);
    } catch (e) {
      if (alive.current) setError(e instanceof Error ? e.message : 'Something went wrong');
    } finally {
      if (alive.current) {
        setLoading(false);
        setRefreshing(false);
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  useEffect(() => {
    alive.current = true;
    run();
    return () => {
      alive.current = false;
    };
  }, [run]);

  return { data, error, loading, refreshing, reload: () => run(false), refresh: () => run(true), setData };
}

/** Wraps a mutation with busy + error state. Returns the result, or undefined on failure. */
export function useAction() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const run = useCallback(async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      return await fn();
    } catch (e) {
      if (e instanceof ApiError) {
        setError(e.message);
        setFieldErrors(e.fieldErrors);
      } else {
        setError(e instanceof Error ? e.message : 'Something went wrong');
      }
      return undefined;
    } finally {
      setBusy(false);
    }
  }, []);

  return { busy, error, fieldErrors, run, setError };
}

import { useEffect, useState } from 'react';
import { toErrorMessage } from '../utils/errors';
import { IDLE, LOADING, failure, success, type AsyncState } from './asyncState';

export type Loader<T> = () => Promise<T>;

interface Settled<T> {
  readonly loader: Loader<T>;
  readonly state: AsyncState<T>;
}

/**
 * Runs `loader` whenever its identity changes and tracks the result.
 *
 * Pass `null` to stay idle (e.g. no account selected). Callers memoize the loader with `useCallback`
 * / `useMemo`, so its identity is the dependency. Results are tagged with the loader that produced
 * them: a stale response is shown as `loading`, never as data for the wrong request, and no state is
 * set synchronously inside the effect.
 */
export function useAsync<T>(loader: Loader<T> | null): AsyncState<T> {
  const [settled, setSettled] = useState<Settled<T> | null>(null);

  useEffect(() => {
    if (!loader) return;
    let active = true;
    loader().then(
      (data) => {
        if (active) setSettled({ loader, state: success(data) });
      },
      (err: unknown) => {
        if (active) setSettled({ loader, state: failure(toErrorMessage(err)) });
      },
    );
    return () => {
      active = false;
    };
  }, [loader]);

  if (!loader) return IDLE;
  if (settled?.loader !== loader) return LOADING;
  return settled.state;
}

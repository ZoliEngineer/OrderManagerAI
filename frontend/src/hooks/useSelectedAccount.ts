import { startTransition } from 'react';
import { useSearchParams } from 'react-router';
import { toAccountId, type AccountId } from '../types/account';

const PARAM = 'account';

/**
 * The selected trade account lives in the URL (`?account=<id>`), not in component state, so it
 * survives reloads, can be deep-linked, and follows the browser's back / forward buttons.
 */
export function useSelectedAccount(): readonly [AccountId | null, (id: AccountId) => void] {
  const [params, setParams] = useSearchParams();
  const value = params.get(PARAM);

  function select(id: AccountId): void {
    // A transition: pages that suspend on the new account's data keep showing the old data meanwhile,
    // instead of flashing back to their loading fallback.
    startTransition(() => {
      setParams((prev) => {
        const next = new URLSearchParams(prev);
        next.set(PARAM, id);
        return next;
      });
    });
  }

  return [value ? toAccountId(value) : null, select];
}

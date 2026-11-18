import { getAccounts } from '../api/accountApi';
import type { AccountSummary } from '../types/account';
import type { AsyncState } from './asyncState';
import { useAsync } from './useAsync';

// `getAccounts` is a module-level function, so its identity is stable: it loads once per mount.
export function useAccounts(): AsyncState<AccountSummary[]> {
  return useAsync(getAccounts);
}

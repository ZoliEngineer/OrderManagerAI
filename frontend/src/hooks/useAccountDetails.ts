import { useMemo } from 'react';
import { getAccountDetails } from '../api/accountApi';
import type { AccountDetails, AccountId } from '../types/account';
import type { AsyncState } from './asyncState';
import { useAsync } from './useAsync';

export function useAccountDetails(accountId: AccountId | null): AsyncState<AccountDetails> {
  const loader = useMemo(() => (accountId ? () => getAccountDetails(accountId) : null), [accountId]);
  return useAsync(loader);
}

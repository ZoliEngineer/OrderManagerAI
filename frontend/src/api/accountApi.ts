import { getConfig } from '../config';
import type { AccountDetails, AccountId, AccountSummary } from '../types/account';
import { request } from './client';
import { cached } from './resource';

const accounts = <T>(path: string): Promise<T> => request<T>(getConfig().accountsApiUrl, path);

export const getAccounts = (): Promise<AccountSummary[]> => accounts('/api/accounts');

export const getAccountDetails = (id: AccountId): Promise<AccountDetails> =>
  accounts(`/api/accounts/${encodeURIComponent(id)}`);

/* Cached promises for `use()`; see resource.ts. */
export const accountsResource = (): Promise<AccountSummary[]> => cached('accounts', getAccounts);

export const accountDetailsResource = (id: AccountId): Promise<AccountDetails> =>
  cached(`account:${id}`, () => getAccountDetails(id));

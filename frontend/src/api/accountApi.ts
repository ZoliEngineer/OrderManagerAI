import { getConfig } from '../config';
import type { AccountDetails, AccountId, AccountSummary } from '../types/account';
import { request } from './client';

const accounts = <T>(path: string): Promise<T> => request<T>(getConfig().accountsApiUrl, path);

export const getAccounts = (): Promise<AccountSummary[]> => accounts('/api/accounts');

export const getAccountDetails = (id: AccountId): Promise<AccountDetails> =>
  accounts(`/api/accounts/${encodeURIComponent(id)}`);

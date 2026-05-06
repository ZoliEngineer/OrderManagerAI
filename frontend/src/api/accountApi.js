import { accountsRequest } from './client';

export function getAccounts() {
  return accountsRequest('/api/accounts');
}

export function getAccountDetails(id) {
  return accountsRequest(`/api/accounts/${id}`);
}

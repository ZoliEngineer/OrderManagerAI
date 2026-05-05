import request from './client';

export function getAccounts() {
  return request('/api/accounts');
}

export function getAccountDetails(id) {
  return request(`/api/accounts/${id}`);
}

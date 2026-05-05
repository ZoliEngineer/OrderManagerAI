import { msalInstance, apiTokenRequest } from '../auth/msalConfig';

const BASE_URL = process.env.REACT_APP_API_URL ?? '';
const ACCOUNTS_BASE_URL = process.env.REACT_APP_ACCOUNTS_API_URL ?? '';

export async function getToken() {
  const account = msalInstance.getActiveAccount() ?? msalInstance.getAllAccounts()[0];
  const result = await msalInstance.acquireTokenSilent({ ...apiTokenRequest, account });
  return result.accessToken;
}

async function request(path, baseUrl = BASE_URL) {
  const token = await getToken();
  const response = await fetch(`${baseUrl}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

export function accountsRequest(path) {
  return request(path, ACCOUNTS_BASE_URL);
}

export default request;

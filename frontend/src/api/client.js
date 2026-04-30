import { msalInstance, apiTokenRequest } from '../auth/msalConfig';

const BASE_URL = process.env.REACT_APP_API_URL ?? '';

async function getToken() {
  const account = msalInstance.getActiveAccount() ?? msalInstance.getAllAccounts()[0];
  const result = await msalInstance.acquireTokenSilent({ ...apiTokenRequest, account });
  return result.accessToken;
}

async function request(path) {
  const token = await getToken();
  const response = await fetch(`${BASE_URL}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

export default request;

import { getUserManager } from '../auth/oidc';
import { config } from '../config';

export async function getToken() {
  const manager = getUserManager();
  let user = await manager.getUser();
  if (!user || user.expired) {
    user = await manager.signinSilent();
  }
  return user.access_token;
}

async function request(path, baseUrl = config.apiUrl) {
  const token = await getToken();
  const response = await fetch(`${baseUrl}${path}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

export function accountsRequest(path) {
  return request(path, config.accountsApiUrl);
}

export default request;

import { UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { config } from '../config';

// Generic OIDC (Cognito hosted UI, Entra ID, Keycloak, ...) with authorization code + PKCE.
let userManager;

export function getUserManager() {
  if (!userManager) {
    const { authority, clientId, scope } = config.oidc;
    if (!authority) throw new Error('OIDC authority is not configured (config.json or REACT_APP_OIDC_AUTHORITY)');
    if (!clientId) throw new Error('OIDC client id is not configured (config.json or REACT_APP_OIDC_CLIENT_ID)');
    userManager = new UserManager({
      authority,
      client_id: clientId,
      redirect_uri: window.location.origin + '/',
      response_type: 'code',
      scope: `openid ${scope}`.trim(),
      userStore: new WebStorageStateStore({ store: window.sessionStorage }),
      automaticSilentRenew: true,
    });
  }
  return userManager;
}

export async function signOut() {
  await getUserManager().removeUser();
  // Cognito has no end_session_endpoint in its discovery document; it needs its own /logout URL.
  window.location.assign(config.oidc.logoutUrl || '/');
}

export function onSigninCallback() {
  window.history.replaceState({}, document.title, window.location.pathname);
}

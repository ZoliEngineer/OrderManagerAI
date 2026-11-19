import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import { getConfig } from '../config';

// Generic OIDC (Cognito hosted UI, Entra ID, Keycloak, ...) with authorization code + PKCE.
let userManager: UserManager | undefined;

export function getUserManager(): UserManager {
  if (userManager) return userManager;

  const { authority, clientId, scope } = getConfig().oidc;
  if (!authority) throw new Error('OIDC authority is not configured (config.json or VITE_OIDC_AUTHORITY)');
  if (!clientId) throw new Error('OIDC client id is not configured (config.json or VITE_OIDC_CLIENT_ID)');

  userManager = new UserManager({
    authority,
    client_id: clientId,
    redirect_uri: `${window.location.origin}/`,
    response_type: 'code',
    scope: `openid ${scope}`.trim(),
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true,
  });
  return userManager;
}

export async function getAccessToken(): Promise<string> {
  const manager = getUserManager();
  const cached = await manager.getUser();
  const user = cached && !cached.expired ? cached : await manager.signinSilent();
  // signinSilent() resolves to null when there is no session to renew; the JS version crashed here.
  if (!user) throw new Error('Not signed in');
  return user.access_token;
}

export async function signOut(): Promise<void> {
  await getUserManager().removeUser();
  window.location.assign(getConfig().oidc.logoutUrl || '/');
}

/** Starts the login redirect, remembering the current page so the user lands back on it. */
export async function signIn(): Promise<void> {
  const returnTo = `${window.location.pathname}${window.location.search}`;
  await getUserManager().signinRedirect({ state: returnTo });
}

/**
 * Where to go after the login callback. The redirect URI stays the origin root (so the IdP's allowed
 * callback URLs don't change); the page the user started on travels in the OIDC `state`.
 * Only same-origin paths are accepted, so the state cannot be used as an open redirect.
 */
export function returnPath(user: User | undefined): string {
  const state = user?.state;
  return typeof state === 'string' && state.startsWith('/') && !state.startsWith('//') ? state : '/';
}

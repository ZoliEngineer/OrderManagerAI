// Runtime configuration. One build artifact serves every environment: at startup the app fetches
// /config.json (written by the deploy step, served next to index.html). REACT_APP_* variables are the
// fallback for local development and tests, where no config.json exists.
const fromEnv = () => ({
  apiUrl: process.env.REACT_APP_API_URL ?? '',
  accountsApiUrl: process.env.REACT_APP_ACCOUNTS_API_URL ?? '',
  ordersApiUrl: process.env.REACT_APP_ORDERS_API_URL ?? '',
  wsBase: process.env.REACT_APP_WS_BASE ?? '',
  oidc: {
    authority: process.env.REACT_APP_OIDC_AUTHORITY ?? '',
    clientId: process.env.REACT_APP_OIDC_CLIENT_ID ?? '',
    scope: process.env.REACT_APP_OIDC_SCOPE ?? '',
    logoutUrl: process.env.REACT_APP_OIDC_LOGOUT_URL ?? '',
  },
});

export const config = fromEnv();

export async function loadRuntimeConfig() {
  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    const isJson = (res.headers?.get('content-type') ?? '').includes('json');
    if (res.ok && isJson) {
      const runtime = await res.json();
      Object.assign(config, runtime, { oidc: { ...config.oidc, ...runtime.oidc } });
    }
  } catch {
    // no config.json (dev server returns index.html) — keep the REACT_APP_* values
  }
  return config;
}

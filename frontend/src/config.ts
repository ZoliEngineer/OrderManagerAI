// Runtime configuration. One build artifact serves every environment: at startup the app fetches
// /config.json (written by the deploy step, served next to index.html). VITE_* variables are the
// fallback for local development and tests, where no config.json exists.

export interface OidcConfig {
  readonly authority: string;
  readonly clientId: string;
  readonly scope: string;
  /** Optional: Cognito has no end_session_endpoint and needs its own /logout URL. */
  readonly logoutUrl: string;
}

export interface RuntimeConfig {
  readonly apiUrl: string;
  readonly accountsApiUrl: string;
  readonly ordersApiUrl: string;
  readonly wsBase: string;
  readonly oidc: OidcConfig;
}

/** Shape of /config.json: every field may be omitted, nested objects included. */
type ConfigOverrides = Partial<Omit<RuntimeConfig, 'oidc'>> & { readonly oidc?: Partial<OidcConfig> };

const env = import.meta.env;

const defaults: RuntimeConfig = {
  apiUrl: env.VITE_API_URL ?? '',
  accountsApiUrl: env.VITE_ACCOUNTS_API_URL ?? '',
  ordersApiUrl: env.VITE_ORDERS_API_URL ?? '',
  wsBase: env.VITE_WS_BASE ?? '',
  oidc: {
    authority: env.VITE_OIDC_AUTHORITY ?? '',
    clientId: env.VITE_OIDC_CLIENT_ID ?? '',
    scope: env.VITE_OIDC_SCOPE ?? '',
    logoutUrl: env.VITE_OIDC_LOGOUT_URL ?? '',
  },
};

export function mergeConfig(base: RuntimeConfig, overrides: ConfigOverrides): RuntimeConfig {
  return { ...base, ...overrides, oidc: { ...base.oidc, ...overrides.oidc } };
}

let current: RuntimeConfig = defaults;

/** The active configuration. A function rather than an exported `let`, so callers always see the loaded value. */
export function getConfig(): RuntimeConfig {
  return current;
}

export async function loadRuntimeConfig(): Promise<RuntimeConfig> {
  try {
    const res = await fetch('/config.json', { cache: 'no-store' });
    const isJson = (res.headers.get('content-type') ?? '').includes('json');
    if (res.ok && isJson) {
      // Trust boundary: config.json is written by our own deploy step, so it is cast, not validated.
      current = mergeConfig(defaults, (await res.json()) as ConfigOverrides);
    }
  } catch {
    // no config.json (dev server returns index.html) — keep the VITE_* values
  }
  return current;
}

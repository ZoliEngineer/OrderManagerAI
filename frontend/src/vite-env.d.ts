/// <reference types="vite/client" />

/** Declares the VITE_* variables so `import.meta.env.VITE_TYPO` is a compile error. */
interface ImportMetaEnv {
  readonly VITE_API_URL?: string;
  readonly VITE_ACCOUNTS_API_URL?: string;
  readonly VITE_ORDERS_API_URL?: string;
  readonly VITE_WS_BASE?: string;
  readonly VITE_OIDC_AUTHORITY?: string;
  readonly VITE_OIDC_CLIENT_ID?: string;
  readonly VITE_OIDC_SCOPE?: string;
  readonly VITE_OIDC_LOGOUT_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

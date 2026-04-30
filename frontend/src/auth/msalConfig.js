import { PublicClientApplication, LogLevel } from "@azure/msal-browser";

if (!process.env.REACT_APP_AAD_CLIENT_ID) throw new Error('REACT_APP_AAD_CLIENT_ID is not set');
if (!process.env.REACT_APP_AAD_TENANT_ID) throw new Error('REACT_APP_AAD_TENANT_ID is not set');
if (!process.env.REACT_APP_AAD_SCOPE) throw new Error('REACT_APP_AAD_SCOPE is not set');

export const msalConfig = {
  auth: {
    clientId: process.env.REACT_APP_AAD_CLIENT_ID,
    authority: `https://login.microsoftonline.com/${process.env.REACT_APP_AAD_TENANT_ID}`,
    redirectUri: window.location.origin,
  },
  cache: {
    cacheLocation: "sessionStorage",
  },
  system: {
    loggerOptions: {
      loggerCallback: (level, message) => {
        if (level === LogLevel.Error) console.error(message);
      },
    },
  },
};

// Used only for sign-in — MSAL adds openid/profile/offline_access automatically.
// The API scope is included so the resulting cache entry correlates with apiTokenRequest,
// meaning acquireTokenSilent can satisfy both from the same cached token.
export const loginRequest = {
  scopes: [process.env.REACT_APP_AAD_SCOPE],
};

// Used when acquiring tokens for API calls
export const apiTokenRequest = {
  scopes: [process.env.REACT_APP_AAD_SCOPE],
};

export const msalInstance = new PublicClientApplication(msalConfig);

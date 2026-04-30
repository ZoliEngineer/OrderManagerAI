import { PublicClientApplication, LogLevel } from "@azure/msal-browser";

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

// Used only for sign-in — MSAL adds openid/profile/offline_access automatically
export const loginRequest = {
  scopes: [],
};

// Used when acquiring tokens for API calls
export const apiTokenRequest = {
  scopes: [process.env.REACT_APP_AAD_SCOPE],
};

export const msalInstance = new PublicClientApplication(msalConfig);

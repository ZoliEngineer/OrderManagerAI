import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { getUserManager, returnPath } from './auth/oidc';
import { loadRuntimeConfig } from './config';
import { routes } from './routes';
import './styles/global.css';

/** What the three root callbacks have in common (their info objects differ in the optional fields). */
interface ErrorDetails {
  readonly componentStack?: string | undefined;
}

/** One place for every error React reports; swap the console for an error tracker here. */
function reportError(kind: 'caught' | 'uncaught' | 'recoverable', error: unknown, info: ErrorDetails): void {
  console.error(`[react:${kind}]`, error, info.componentStack);
}

// getElementById returns `HTMLElement | null`. Check it instead of asserting with `!`.
const container = document.getElementById('root');
if (!container) throw new Error('index.html is missing the #root element');
const root = createRoot(container, {
  onCaughtError: (error, info) => {
    reportError('caught', error, info);
  },
  onUncaughtError: (error, info) => {
    reportError('uncaught', error, info);
  },
  onRecoverableError: (error, info) => {
    reportError('recoverable', error, info);
  },
});

// Runtime config (/config.json) must be loaded before the OIDC client is built.
void loadRuntimeConfig().then(() => {
  const router = createBrowserRouter(routes);
  root.render(
    <StrictMode>
      <AuthProvider
        userManager={getUserManager()}
        // After the login callback, replace `/?code=…&state=…` with the page the user started on.
        onSigninCallback={(user) => router.navigate(returnPath(user), { replace: true })}
      >
        <RouterProvider router={router} />
      </AuthProvider>
    </StrictMode>,
  );
});

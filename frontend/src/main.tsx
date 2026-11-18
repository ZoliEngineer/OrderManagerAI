import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import { App } from './App';
import { getUserManager, onSigninCallback } from './auth/oidc';
import { loadRuntimeConfig } from './config';
import './styles/global.css';

// getElementById returns `HTMLElement | null`. Check it instead of asserting with `!`.
const container = document.getElementById('root');
if (!container) throw new Error('index.html is missing the #root element');
const root = createRoot(container);

// Runtime config (/config.json) must be loaded before the OIDC client is built.
void loadRuntimeConfig().then(() => {
  root.render(
    <StrictMode>
      <AuthProvider userManager={getUserManager()} onSigninCallback={onSigninCallback}>
        <App />
      </AuthProvider>
    </StrictMode>,
  );
});

import ReactDOM from 'react-dom/client';
import { AuthProvider } from 'react-oidc-context';
import './styles/global.css';
import App from './App';
import { loadRuntimeConfig } from './config';
import { getUserManager, onSigninCallback } from './auth/oidc';

const root = ReactDOM.createRoot(document.getElementById('root'));

// Runtime config (/config.json) must be loaded before the OIDC client is built.
loadRuntimeConfig().then(() => {
  root.render(
    <AuthProvider userManager={getUserManager()} onSigninCallback={onSigninCallback}>
      <App />
    </AuthProvider>
  );
});

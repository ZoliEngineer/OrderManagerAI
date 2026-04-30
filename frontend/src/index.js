import ReactDOM from 'react-dom/client';
import { MsalProvider } from '@azure/msal-react';
import './styles/global.css';
import App from './App';
import { msalInstance } from './auth/msalConfig';

const root = ReactDOM.createRoot(document.getElementById('root'));

// MSAL v3 must be initialized before use so the redirect response is
// processed and the account is in cache before React first renders.
msalInstance.initialize().then(() => {
  root.render(
    <MsalProvider instance={msalInstance}>
      <App />
    </MsalProvider>
  );
});

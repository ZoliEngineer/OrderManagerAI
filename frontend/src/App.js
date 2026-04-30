import { useIsAuthenticated, useMsal } from '@azure/msal-react';
import { InteractionStatus } from '@azure/msal-browser';
import MarketDataTable from './components/MarketDataTable';
import { loginRequest } from './auth/msalConfig';
import './styles/layout.css';

const NAV_ITEMS = [
  { icon: '▦', label: 'Market Data',  active: true  },
  { icon: '◈', label: 'Portfolio',    active: false },
  { icon: '⇅', label: 'Orders',       active: false },
  { icon: '⊕', label: 'Buy / Sell',   active: false },
];

function App() {
  const isAuthenticated = useIsAuthenticated();
  const { instance, inProgress } = useMsal();

  if (inProgress !== InteractionStatus.None) {
    return null;
  }

  if (!isAuthenticated) {
    return (
      <div className="app-shell" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <button className="btn-primary" onClick={() => instance.loginRedirect(loginRequest)}>
          Sign in with Microsoft
        </button>
      </div>
    );
  }

  return (
    <div className="app-shell">

      <header className="topbar">
        <span className="topbar-logo">Order<span>Manager</span></span>
        <div className="topbar-divider" />
        <span className="topbar-status">Market Open</span>
        <button className="btn-secondary" onClick={() => instance.logoutRedirect()}>Sign out</button>
      </header>

      <div className="app-body">

        <nav className="sidebar">
          <span className="sidebar-section-label">Navigation</span>
          {NAV_ITEMS.map((item) => (
            <div key={item.label} className={`sidebar-item${item.active ? ' active' : ''}`}>
              <span className="sidebar-item-icon">{item.icon}</span>
              {item.label}
            </div>
          ))}
        </nav>

        <main className="main-content">
          <div className="page-header">
            <h1 className="page-title">Market Data</h1>
            <span className="page-subtitle">S&amp;P 500 — Top 10</span>
          </div>

          <div className="card">
            <div className="card-header">
              <span className="card-title">Equities</span>
            </div>
            <MarketDataTable />
          </div>
        </main>

      </div>
    </div>
  );
}

export default App;

import { useState } from 'react';
import { useIsAuthenticated, useMsal } from '@azure/msal-react';
import { InteractionStatus } from '@azure/msal-browser';
import MarketDataTable from './components/MarketDataTable';
import AccountSelector from './components/AccountSelector';
import PortfolioPage from './components/PortfolioPage';
import useMarketStatus from './hooks/useMarketStatus';
import { loginRequest } from './auth/msalConfig';
import './styles/layout.css';
import './styles/global.css';

function marketStatusLabel(status, loading) {
  if (loading || !status) return 'Market …';
  if (status.isOpen) return 'Market Open';
  if (status.session === 'pre-market') return 'Pre-Market';
  if (status.session === 'post-market') return 'After Hours';
  if (status.holiday) return `Closed — ${status.holiday}`;
  return 'Market Closed';
}

const NAV_ITEMS = [
  { id: 'market-data', icon: '▦', label: 'Market Data' },
  { id: 'portfolio',   icon: '◈', label: 'Portfolio'   },
  { id: 'orders',      icon: '⇅', label: 'Orders'      },
  { id: 'buy-sell',    icon: '⊕', label: 'Buy / Sell'  },
];

function MarketStatusLabel() {
  const { status, loading } = useMarketStatus();
  return <span className="topbar-status">{marketStatusLabel(status, loading)}</span>;
}

function App() {
  const isAuthenticated = useIsAuthenticated();
  const { instance, inProgress } = useMsal();
  const [activePage, setActivePage] = useState('market-data');
  const [selectedAccountId, setSelectedAccountId] = useState('');

  if (inProgress !== InteractionStatus.None) {
    return null;
  }

  if (!isAuthenticated) {
    return (
      <div className="app-shell">
        <div className="signin-screen">
          <span className="signin-logo">Order<span>Manager</span></span>
          <div className="signin-card">
            <p>Sign in with your Microsoft account to continue.</p>
            <button className="btn-primary" onClick={() => instance.loginRedirect(loginRequest)}>
              Sign in with Microsoft
            </button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="app-shell">

      <header className="topbar">
        <span className="topbar-logo">Order<span>Manager</span></span>
        <div className="topbar-divider" />
        <MarketStatusLabel />
        <div className="topbar-spacer" />
        <AccountSelector selectedId={selectedAccountId} onSelect={setSelectedAccountId} />
        <div className="topbar-divider" />
        <button className="btn-secondary" onClick={() => instance.logoutRedirect()}>Sign out</button>
      </header>

      <div className="app-body">

        <nav className="sidebar">
          <span className="sidebar-section-label">Navigation</span>
          {NAV_ITEMS.map((item) => (
            <div
              key={item.id}
              className={`sidebar-item${activePage === item.id ? ' active' : ''}`}
              onClick={() => setActivePage(item.id)}
            >
              <span className="sidebar-item-icon">{item.icon}</span>
              {item.label}
            </div>
          ))}
        </nav>

        <main className="main-content">
          {activePage === 'market-data' && (
            <>
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
            </>
          )}

          {activePage === 'portfolio' && (
            <PortfolioPage selectedAccountId={selectedAccountId} />
          )}
        </main>

      </div>
    </div>
  );
}

export default App;

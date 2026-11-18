import { useState } from 'react';
import { useAuth } from 'react-oidc-context';
import { signOut } from './auth/oidc';
import { AccountSelector } from './components/AccountSelector';
import { MarketDataTable } from './components/MarketDataTable';
import { OrdersPage } from './components/OrdersPage';
import { PortfolioPage } from './components/PortfolioPage';
import { useMarketStatus } from './hooks/useMarketStatus';
import type { AccountId } from './types/account';
import { marketStatusLabel } from './utils/marketStatus';
import './styles/layout.css';
import './styles/global.css';

interface NavItem {
  readonly id: string;
  readonly icon: string;
  readonly label: string;
}

/**
 * `as const` keeps the ids as literals, and `satisfies` checks the shape without widening them.
 * `PageId` is then derived from the data: 'market-data' | 'portfolio' | 'orders' | 'buy-sell'.
 */
const NAV_ITEMS = [
  { id: 'market-data', icon: '▦', label: 'Market Data' },
  { id: 'portfolio', icon: '◈', label: 'Portfolio' },
  { id: 'orders', icon: '⇅', label: 'Orders' },
  { id: 'buy-sell', icon: '⊕', label: 'Buy / Sell' },
] as const satisfies readonly NavItem[];

type PageId = (typeof NAV_ITEMS)[number]['id'];

function MarketStatusLabel() {
  const status = useMarketStatus();
  return <span className="topbar-status">{marketStatusLabel(status)}</span>;
}

function SignInScreen({ onSignIn }: { readonly onSignIn: () => void }) {
  return (
    <div className="app-shell">
      <div className="signin-screen">
        <span className="signin-logo">
          Order<span>Manager</span>
        </span>
        <div className="signin-card">
          <p>Sign in to continue.</p>
          <button className="btn-primary" onClick={onSignIn}>
            Sign in
          </button>
        </div>
      </div>
    </div>
  );
}

function MarketDataPage({ selectedAccountId }: { readonly selectedAccountId: AccountId | null }) {
  return (
    <>
      <div className="page-header">
        <h1 className="page-title">Market Data</h1>
        <span className="page-subtitle">S&amp;P 500 — Top 10</span>
      </div>
      <div className="card">
        <div className="card-header">
          <span className="card-title">Equities</span>
        </div>
        <MarketDataTable selectedAccountId={selectedAccountId} />
      </div>
    </>
  );
}

export function App() {
  const auth = useAuth();
  const [activePage, setActivePage] = useState<PageId>('market-data');
  const [selectedAccountId, setSelectedAccountId] = useState<AccountId | null>(null);

  if (auth.isLoading) return null;

  if (!auth.isAuthenticated) {
    return <SignInScreen onSignIn={() => void auth.signinRedirect()} />;
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <span className="topbar-logo">
          Order<span>Manager</span>
        </span>
        <div className="topbar-divider" />
        <MarketStatusLabel />
        <div className="topbar-spacer" />
        <AccountSelector selectedId={selectedAccountId} onSelect={setSelectedAccountId} />
        <div className="topbar-divider" />
        <button className="btn-secondary" onClick={() => void signOut()}>
          Sign out
        </button>
      </header>

      <div className="app-body">
        <nav className="sidebar">
          <span className="sidebar-section-label">Navigation</span>
          {NAV_ITEMS.map((item) => (
            <div
              key={item.id}
              className={`sidebar-item${activePage === item.id ? ' active' : ''}`}
              onClick={() => {
                setActivePage(item.id);
              }}
            >
              <span className="sidebar-item-icon">{item.icon}</span>
              {item.label}
            </div>
          ))}
        </nav>

        <main className="main-content">
          {activePage === 'market-data' && <MarketDataPage selectedAccountId={selectedAccountId} />}
          {activePage === 'portfolio' && <PortfolioPage selectedAccountId={selectedAccountId} />}
          {activePage === 'orders' && <OrdersPage selectedAccountId={selectedAccountId} />}
        </main>
      </div>
    </div>
  );
}

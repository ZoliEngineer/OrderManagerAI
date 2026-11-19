import { Suspense } from 'react';
import { useAuth } from 'react-oidc-context';
import { NavLink, Outlet, useLocation } from 'react-router';
import { signIn, signOut } from './auth/oidc';
import { AccountSelector } from './components/AccountSelector';
import { ErrorBoundary } from './components/ErrorBoundary';
import { useMarketStatus } from './hooks/useMarketStatus';
import { useSelectedAccount } from './hooks/useSelectedAccount';
import { retryFailed } from './api/resource';
import { toErrorMessage } from './utils/errors';
import { marketStatusLabel } from './utils/marketStatus';
import './styles/layout.css';

interface NavItem {
  readonly path: `/${string}`;
  readonly icon: string;
  readonly label: string;
}

const NAV_ITEMS = [
  { path: '/market', icon: '▦', label: 'Market Data' },
  { path: '/portfolio', icon: '◈', label: 'Portfolio' },
  { path: '/orders', icon: '⇅', label: 'Orders' },
] as const satisfies readonly NavItem[];

function MarketStatusLabel() {
  const status = useMarketStatus();
  return <span className="topbar-status">{marketStatusLabel(status)}</span>;
}

function SignInScreen() {
  return (
    <div className="app-shell">
      <title>Sign in · OrderManager</title>
      <div className="signin-screen">
        <span className="signin-logo">
          Order<span>Manager</span>
        </span>
        <div className="signin-card">
          <p>Sign in to continue.</p>
          <button className="btn-primary" onClick={() => void signIn()}>
            Sign in
          </button>
        </div>
      </div>
    </div>
  );
}

function PageError({ message, retry }: { readonly message: string; readonly retry: () => void }) {
  return (
    <div className="card">
      <p className="status-error">Something went wrong: {message}</p>
      <button className="btn-secondary" onClick={retry}>
        Retry
      </button>
    </div>
  );
}

/** The layout route: auth gate, top bar, sidebar, and the current page in `<Outlet />`. */
export function App() {
  const auth = useAuth();
  const { pathname, search } = useLocation();
  const [selectedAccountId, selectAccount] = useSelectedAccount();

  // Nothing renders (and so no route redirects) until the OIDC callback has been processed.
  if (auth.isLoading) return null;
  if (!auth.isAuthenticated) return <SignInScreen />;

  return (
    <div className="app-shell">
      <header className="topbar">
        <span className="topbar-logo">
          Order<span>Manager</span>
        </span>
        <div className="topbar-divider" />
        <MarketStatusLabel />
        <div className="topbar-spacer" />
        <AccountSelector selectedId={selectedAccountId} onSelect={selectAccount} />
        <div className="topbar-divider" />
        <button className="btn-secondary" onClick={() => void signOut()}>
          Sign out
        </button>
      </header>

      <div className="app-body">
        <nav className="sidebar" aria-label="Main">
          <span className="sidebar-section-label">Navigation</span>
          {NAV_ITEMS.map((item) => (
            // NavLink renders a real <a> with `aria-current="page"` and the `active` class when it matches.
            // The search string is kept, so switching pages keeps the selected account.
            <NavLink key={item.path} to={{ pathname: item.path, search }} className="sidebar-item">
              <span className="sidebar-item-icon" aria-hidden="true">
                {item.icon}
              </span>
              {item.label}
            </NavLink>
          ))}
        </nav>

        <main className="main-content">
          {/* Keyed by path: navigating away from a failed page clears its error. */}
          <ErrorBoundary
            key={pathname}
            onReset={retryFailed}
            fallback={(error, reset) => <PageError message={toErrorMessage(error)} retry={reset} />}
          >
            <Suspense fallback={<p className="status-loading">Loading…</p>}>
              <Outlet />
            </Suspense>
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}

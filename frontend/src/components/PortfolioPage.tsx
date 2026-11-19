import { use, type ReactNode } from 'react';
import { accountDetailsResource } from '../api/accountApi';
import { useSelectedAccount } from '../hooks/useSelectedAccount';
import type { AccountDetails, AccountId } from '../types/account';
import { formatCurrency } from '../utils/format';
import { DataBoundary } from './ErrorBoundary';

function AccountStats({ details }: { readonly details: AccountDetails }) {
  return (
    <dl className="portfolio-stats">
      <div className="portfolio-stat">
        <dt>Account</dt>
        <dd>{details.displayName}</dd>
      </div>
      <div className="portfolio-stat">
        <dt>Cash Balance</dt>
        <dd className="portfolio-stat-value">{formatCurrency(details.cashBalance)}</dd>
      </div>
      <div className="portfolio-stat">
        <dt>Available to Use</dt>
        <dd className="portfolio-stat-value">{formatCurrency(details.buyingPower)}</dd>
      </div>
    </dl>
  );
}

interface PortfolioLayoutProps {
  readonly subtitle?: string;
  readonly summary: ReactNode;
}

/** The page frame, shared by the loading, error, empty and loaded states. */
function PortfolioLayout({ subtitle, summary }: PortfolioLayoutProps) {
  return (
    <div className="portfolio-page">
      <div className="page-header">
        <h1 className="page-title">Portfolio</h1>
        {subtitle && <span className="page-subtitle">{subtitle}</span>}
      </div>

      <div className="portfolio-summary-row">
        <div className="card portfolio-summary-card">
          <div className="card-header">
            <span className="card-title">Account Summary</span>
          </div>
          <div className="portfolio-summary-body">{summary}</div>
        </div>
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">Holdings</span>
        </div>
        <div className="portfolio-holdings-placeholder">
          <span className="portfolio-placeholder-text">Holdings will appear here.</span>
        </div>
      </div>
    </div>
  );
}

function LoadedPortfolio({ accountId }: { readonly accountId: AccountId }) {
  const details = use(accountDetailsResource(accountId));
  return <PortfolioLayout subtitle={details.displayName} summary={<AccountStats details={details} />} />;
}

export function PortfolioPage() {
  const [accountId] = useSelectedAccount();

  return (
    <>
      <title>Portfolio · OrderManager</title>
      {accountId === null ? (
        <PortfolioLayout summary={<p className="portfolio-empty-hint">Select an account to view details.</p>} />
      ) : (
        <DataBoundary
          loading={<PortfolioLayout summary={<p className="portfolio-loading">Loading…</p>} />}
          error={(_message, retry) => (
            <PortfolioLayout
              summary={
                <p className="portfolio-error">
                  Failed to load account details.{' '}
                  <button className="btn-secondary" onClick={retry}>
                    Retry
                  </button>
                </p>
              }
            />
          )}
        >
          <LoadedPortfolio accountId={accountId} />
        </DataBoundary>
      )}
    </>
  );
}

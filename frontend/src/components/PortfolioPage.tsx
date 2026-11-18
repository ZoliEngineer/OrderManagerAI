import { useAccountDetails } from '../hooks/useAccountDetails';
import type { AccountDetails, AccountId } from '../types/account';
import { assertNever } from '../utils/errors';
import { formatCurrency } from '../utils/format';

interface PortfolioPageProps {
  readonly selectedAccountId: AccountId | null;
}

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

export function PortfolioPage({ selectedAccountId }: PortfolioPageProps) {
  const details = useAccountDetails(selectedAccountId);

  function renderSummary() {
    switch (details.status) {
      case 'idle':
        return <p className="portfolio-empty-hint">Select an account to view details.</p>;
      case 'loading':
        return <p className="portfolio-loading">Loading…</p>;
      case 'error':
        return <p className="portfolio-error">Failed to load account details.</p>;
      case 'success':
        return <AccountStats details={details.data} />;
      default:
        return assertNever(details);
    }
  }

  return (
    <div className="portfolio-page">
      <div className="page-header">
        <h1 className="page-title">Portfolio</h1>
        {details.status === 'success' && <span className="page-subtitle">{details.data.displayName}</span>}
      </div>

      <div className="portfolio-summary-row">
        <div className="card portfolio-summary-card">
          <div className="card-header">
            <span className="card-title">Account Summary</span>
          </div>
          <div className="portfolio-summary-body">{renderSummary()}</div>
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

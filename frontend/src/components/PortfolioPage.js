import useAccountDetails from '../hooks/useAccountDetails';

function formatCurrency(value) {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

export default function PortfolioPage({ selectedAccountId }) {
  const { details, loading, error } = useAccountDetails(selectedAccountId);

  return (
    <div className="portfolio-page">
      <div className="page-header">
        <h1 className="page-title">Portfolio</h1>
        {details && <span className="page-subtitle">{details.displayName}</span>}
      </div>

      <div className="portfolio-summary-row">
        <div className="card portfolio-summary-card">
          <div className="card-header">
            <span className="card-title">Account Summary</span>
          </div>
          <div className="portfolio-summary-body">
            {!selectedAccountId && (
              <p className="portfolio-empty-hint">Select an account to view details.</p>
            )}
            {selectedAccountId && loading && (
              <p className="portfolio-loading">Loading…</p>
            )}
            {selectedAccountId && error && (
              <p className="portfolio-error">Failed to load account details.</p>
            )}
            {details && !loading && (
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
            )}
          </div>
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

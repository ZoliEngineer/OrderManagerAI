import { MarketDataTable } from './MarketDataTable';

export function MarketDataPage() {
  return (
    <>
      {/* React 19 hoists <title> (and <meta>, <link>) into <head>, wherever it is rendered. */}
      <title>Market Data · OrderManager</title>
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
  );
}

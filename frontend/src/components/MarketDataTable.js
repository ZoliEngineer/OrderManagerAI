import React from 'react';
import useMarketData from '../hooks/useMarketData';
import './MarketDataTable.css';

function MarketDataTable() {
  const { stocks, loading, error } = useMarketData();

  if (loading) return <p className="status-loading">Loading market data...</p>;
  if (error)   return <p className="status-error">Error: {error}</p>;

  return (
    <table className="market-table">
      <thead>
        <tr>
          <th>Ticker</th>
          <th>Name</th>
          <th className="align-right">Price (USD)</th>
        </tr>
      </thead>
      <tbody>
        {stocks.map((stock) => (
          <tr key={stock.ticker}>
            <td><span className="ticker-badge">{stock.ticker}</span></td>
            <td>{stock.name}</td>
            <td className="align-right">
              <span className="price-value">${Number(stock.price).toFixed(2)}</span>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default MarketDataTable;

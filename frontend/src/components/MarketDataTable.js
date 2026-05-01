import React, { useRef, useState, useEffect } from 'react';
import useMarketData from '../hooks/useMarketData';
import './MarketDataTable.css';

function MarketDataTable() {
  const { stocks, loading, error } = useMarketData();
  const prevPrices = useRef({});
  const [flashMap, setFlashMap] = useState({});

  useEffect(() => {
    const newFlashes = {};
    stocks.forEach(({ ticker, price }) => {
      const prev = prevPrices.current[ticker];
      if (prev !== undefined && prev !== price) {
        newFlashes[ticker] = price > prev ? 'flash-up' : 'flash-down';
      }
      prevPrices.current[ticker] = price;
    });

    if (Object.keys(newFlashes).length === 0) return;

    setFlashMap(prev => ({ ...prev, ...newFlashes }));
    const timer = setTimeout(() => {
      setFlashMap(prev => {
        const next = { ...prev };
        Object.keys(newFlashes).forEach(t => delete next[t]);
        return next;
      });
    }, 600);

    return () => clearTimeout(timer);
  }, [stocks]);

  if (loading) return <p className="status-loading">Loading market data...</p>;
  if (error)   return <p className="status-error">Error: {error}</p>;

  return (
    <table className="market-table">
      <thead>
        <tr>
          <th>Ticker</th>
          <th>Name</th>
          <th className="align-right">Price (USD)</th>
          <th className="align-right">Day Change</th>
        </tr>
      </thead>
      <tbody>
        {stocks.map((stock) => {
          const totalChange = Number(stock.totalChange);
          const lastChange  = Number(stock.lastChange);
          const dayDir = totalChange > 0 ? 'price-up' : totalChange < 0 ? 'price-down' : '';
          const tickDir = lastChange  > 0 ? 'price-up' : lastChange  < 0 ? 'price-down' : 'price-flat';
          const sign = v => v > 0 ? '+' : '';

          return (
            <tr key={stock.ticker} className={flashMap[stock.ticker] || ''}>
              <td><span className="ticker-badge">{stock.ticker}</span></td>
              <td>{stock.name}</td>
              <td className="align-right">
                <span className="price-value">
                  ${Number(stock.price).toFixed(2)}
                </span>
                {stock.lastChange != null && (
                  <span className={`price-tick ${tickDir}`}>
                    {lastChange > 0 ? '▲' : lastChange < 0 ? '▼' : ''}{sign(lastChange)}{lastChange.toFixed(2)}
                  </span>
                )}
              </td>
              <td className="align-right">
                {stock.changePercent != null && (
                  <>
                    <span className={`day-change ${dayDir}`}>
                      {sign(Number(stock.changePercent))}{Number(stock.changePercent).toFixed(2)}%
                    </span>
                    {stock.totalChange != null && (
                      <span className={`day-change-pct ${dayDir}`}>
                        {sign(totalChange)}{totalChange.toFixed(2)}
                      </span>
                    )}
                  </>
                )}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

export default MarketDataTable;

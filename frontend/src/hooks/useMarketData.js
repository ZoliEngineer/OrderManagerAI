import { useState, useEffect } from 'react';
import { getMarketData } from '../api/marketDataApi';
import { getToken } from '../api/client';
import { connectMarketDataStream } from '../api/marketDataWebSocket';

function useMarketData() {
  const [stockMap, setStockMap] = useState({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Initial snapshot so the table isn't empty while waiting for the first tick
  useEffect(() => {
    getMarketData()
      .then(data => setStockMap(Object.fromEntries(data.map(s => [s.ticker, s]))))
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  // Live updates — acquire token first, then open WebSocket
  useEffect(() => {
    let disconnect;
    getToken()
      .then(token => {
        disconnect = connectMarketDataStream(
          (stock) => setStockMap(prev => ({ ...prev, [stock.ticker]: stock })),
          (err) => setError(err),
          token
        );
      })
      .catch(() => setError('Authentication error — unable to connect to live prices'));
    return () => disconnect?.();
  }, []);

  const stocks = Object.values(stockMap).sort((a, b) => a.ticker.localeCompare(b.ticker));
  return { stocks, loading, error };
}

export default useMarketData;

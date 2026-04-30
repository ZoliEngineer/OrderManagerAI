import { useState, useEffect } from 'react';
import { msalInstance, apiTokenRequest } from '../auth/msalConfig';
import { getMarketData } from '../api/marketDataApi';
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

  // Live updates — acquire token first, then open WebSocket with it as a query param
  useEffect(() => {
    const account = msalInstance.getActiveAccount() ?? msalInstance.getAllAccounts()[0];
    msalInstance.acquireTokenSilent({ ...apiTokenRequest, account })
      .then(result => connectMarketDataStream(
        (stock) => setStockMap(prev => ({ ...prev, [stock.ticker]: stock })),
        (err) => setError(err),
        result.accessToken
      ))
      .catch(() => setError('Authentication error — unable to connect to live prices'));
  }, []);

  const stocks = Object.values(stockMap).sort((a, b) => a.ticker.localeCompare(b.ticker));
  return { stocks, loading, error };
}

export default useMarketData;

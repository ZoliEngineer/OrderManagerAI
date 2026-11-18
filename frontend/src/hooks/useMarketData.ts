import { useEffect, useMemo, useState } from 'react';
import { getAccessToken } from '../auth/oidc';
import { getMarketData } from '../api/marketDataApi';
import { connectMarketDataStream, type Disconnect } from '../api/marketDataWebSocket';
import type { Stock } from '../types/marketData';
import { toErrorMessage } from '../utils/errors';
import { LOADING, failure, success, type AsyncState } from './asyncState';

type StockMap = Readonly<Record<string, Stock>>;

const byTicker = (stocks: readonly Stock[]): StockMap =>
  Object.fromEntries(stocks.map((stock) => [stock.ticker, stock]));

/** REST snapshot for the first render, then live WebSocket ticks on top of it. */
export function useMarketData(): AsyncState<readonly Stock[]> {
  // null = no data yet. Ticks and the snapshot may arrive in either order.
  const [stocks, setStocks] = useState<StockMap | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    getMarketData().then(
      // Live ticks that beat the snapshot are newer, so they win.
      (snapshot) => {
        if (active) setStocks((live) => ({ ...byTicker(snapshot), ...live }));
      },
      (err: unknown) => {
        if (active) setError(toErrorMessage(err));
      },
    );
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    let disconnect: Disconnect | undefined;
    getAccessToken().then(
      (token) => {
        if (!active) return;
        disconnect = connectMarketDataStream(token, {
          onStock: (stock) => {
            setStocks((prev) => ({ ...prev, [stock.ticker]: stock }));
          },
          onError: setError,
        });
      },
      () => {
        if (active) setError('Authentication error — unable to connect to live prices');
      },
    );
    return () => {
      active = false;
      disconnect?.();
    };
  }, []);

  return useMemo(() => {
    if (error !== null) return failure(error);
    if (stocks === null) return LOADING;
    return success(Object.values(stocks).toSorted((a, b) => a.ticker.localeCompare(b.ticker)));
  }, [stocks, error]);
}

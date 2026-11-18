import { useEffect, useState } from 'react';
import { getMarketStatus } from '../api/marketDataApi';
import type { MarketStatus } from '../types/marketData';
import { toErrorMessage } from '../utils/errors';
import { LOADING, failure, success, type AsyncState } from './asyncState';

const POLL_INTERVAL_MS = 5 * 60 * 1000;

export function useMarketStatus(): AsyncState<MarketStatus> {
  const [state, setState] = useState<AsyncState<MarketStatus>>(LOADING);

  useEffect(() => {
    let active = true;

    async function poll(): Promise<void> {
      try {
        const status = await getMarketStatus();
        if (active) setState(success(status));
      } catch (err: unknown) {
        // A failed poll keeps the last known status rather than blanking the label.
        if (active) setState((prev) => (prev.status === 'success' ? prev : failure(toErrorMessage(err))));
      }
    }

    void poll();
    const interval = setInterval(() => void poll(), POLL_INTERVAL_MS);
    return () => {
      active = false;
      clearInterval(interval);
    };
  }, []);

  return state;
}

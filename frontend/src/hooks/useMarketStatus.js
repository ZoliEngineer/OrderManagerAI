import { useState, useEffect } from 'react';
import { getMarketStatus } from '../api/marketDataApi';

const POLL_INTERVAL_MS = 5 * 60 * 1000;

export default function useMarketStatus() {
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    let cancelled = false;

    async function fetch() {
      try {
        const data = await getMarketStatus();
        if (!cancelled) {
          setStatus(data);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError(e.message);
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetch();
    const interval = setInterval(fetch, POLL_INTERVAL_MS);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  return { status, loading, error };
}

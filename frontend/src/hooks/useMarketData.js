import { useState, useEffect } from 'react';
import { getMarketData } from '../api/marketDataApi';

function useMarketData() {
  const [stocks, setStocks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    getMarketData()
      .then(setStocks)
      .catch((err) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  return { stocks, loading, error };
}

export default useMarketData;

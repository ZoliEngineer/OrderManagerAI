import { useEffect, useState } from 'react';
import { getAccountDetails } from '../api/accountApi';

export default function useAccountDetails(accountId) {
  const [details, setDetails] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  useEffect(() => {
    if (!accountId) {
      setDetails(null);
      return;
    }
    setLoading(true);
    setError(null);
    getAccountDetails(accountId)
      .then(setDetails)
      .catch(setError)
      .finally(() => setLoading(false));
  }, [accountId]);

  return { details, loading, error };
}

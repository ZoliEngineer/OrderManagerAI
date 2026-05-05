import { useEffect, useState } from 'react';
import { getAccounts } from '../api/accountApi';

export default function useAccounts() {
  const [accounts, setAccounts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    getAccounts()
      .then(setAccounts)
      .catch(setError)
      .finally(() => setLoading(false));
  }, []);

  return { accounts, loading, error };
}

import { useState, useEffect } from 'react';
import { getOrders, cancelOrder } from '../api/ordersApi';

export default function useOrders(accountId) {
  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [cancelling, setCancelling] = useState(new Set());

  useEffect(() => {
    if (!accountId) {
      setOrders([]);
      return;
    }
    setLoading(true);
    setError(null);
    getOrders(accountId)
      .then(setOrders)
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [accountId]);

  function cancel(orderId) {
    setCancelling(prev => new Set(prev).add(orderId));
    cancelOrder(orderId)
      .then(() => setOrders(prev => prev.map(o =>
        o.id === orderId ? { ...o, status: 'CANCELLED' } : o
      )))
      .catch(err => setError(err.message))
      .finally(() => setCancelling(prev => {
        const next = new Set(prev);
        next.delete(orderId);
        return next;
      }));
  }

  return { orders, loading, error, cancel, cancelling };
}

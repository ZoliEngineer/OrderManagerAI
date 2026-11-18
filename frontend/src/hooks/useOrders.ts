import { useCallback, useMemo, useState } from 'react';
import { cancelOrder, getOrders } from '../api/ordersApi';
import type { AccountId } from '../types/account';
import type { Order, OrderId } from '../types/order';
import { toErrorMessage } from '../utils/errors';
import { success, type AsyncState } from './asyncState';
import { useAsync } from './useAsync';

export interface UseOrdersResult {
  readonly orders: AsyncState<readonly Order[]>;
  readonly cancel: (orderId: OrderId) => void;
  readonly cancelling: ReadonlySet<OrderId>;
  readonly cancelError: string | null;
}

function withSet<T>(set: ReadonlySet<T>, value: T, present: boolean): ReadonlySet<T> {
  const next = new Set(set);
  if (present) next.add(value);
  else next.delete(value);
  return next;
}

export function useOrders(accountId: AccountId | null): UseOrdersResult {
  const loader = useMemo(() => (accountId ? () => getOrders(accountId) : null), [accountId]);
  const fetched = useAsync(loader);

  const [cancelled, setCancelled] = useState<ReadonlySet<OrderId>>(new Set());
  const [cancelling, setCancelling] = useState<ReadonlySet<OrderId>>(new Set());
  const [cancelError, setCancelError] = useState<string | null>(null);

  const cancel = useCallback((orderId: OrderId) => {
    setCancelling((prev) => withSet(prev, orderId, true));
    setCancelError(null);
    cancelOrder(orderId)
      .then(() => {
        setCancelled((prev) => withSet(prev, orderId, true));
      })
      .catch((err: unknown) => {
        setCancelError(toErrorMessage(err));
      })
      .finally(() => {
        setCancelling((prev) => withSet(prev, orderId, false));
      });
  }, []);

  // Apply successful cancellations on top of the fetched list instead of copying it into state.
  const orders = useMemo((): AsyncState<readonly Order[]> => {
    if (fetched.status !== 'success') return fetched;
    return success(fetched.data.map((o): Order => (cancelled.has(o.id) ? { ...o, status: 'CANCELLED' } : o)));
  }, [fetched, cancelled]);

  return { orders, cancel, cancelling, cancelError };
}

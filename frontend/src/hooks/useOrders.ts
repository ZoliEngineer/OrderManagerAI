import { startTransition, use, useOptimistic, useState } from 'react';
import { cancelOrder, invalidateOrders, ordersResource } from '../api/ordersApi';
import type { AccountId } from '../types/account';
import type { Order, OrderId } from '../types/order';
import { toErrorMessage } from '../utils/errors';

/** An order as shown in the blotter: `pending` marks an optimistic change the server has not confirmed yet. */
export interface BlotterOrder extends Order {
  readonly pending?: true;
}

export interface UseOrdersResult {
  readonly orders: readonly BlotterOrder[];
  readonly cancel: (orderId: OrderId) => void;
  readonly cancelError: string | null;
}

function markCancelled(orders: readonly BlotterOrder[], orderId: OrderId): readonly BlotterOrder[] {
  return orders.map((o) => (o.id === orderId ? { ...o, status: 'CANCELLED', pending: true } : o));
}

/**
 * The account's orders, read with `use()` (the caller must sit inside Suspense and an error boundary),
 * plus an optimistic cancel. Mount it once per account (`key={accountId}`): the account is fixed for the
 * lifetime of the state below.
 */
export function useOrders(accountId: AccountId): UseOrdersResult {
  // The promise is React state, not a fresh `ordersResource()` call per render: render must stay pure
  // (the React Compiler caches calls whose inputs did not change), so a refetch is a state update.
  const [ordersPromise, setOrdersPromise] = useState(() => ordersResource(accountId));
  const fetched: readonly BlotterOrder[] = use(ordersPromise);
  // While a cancel Action is in flight the row already shows CANCELLED. When the Action ends, React
  // drops the optimistic value: on success the refetched list confirms it, on failure it rolls back.
  const [orders, addOptimisticCancel] = useOptimistic(fetched, markCancelled);
  const [cancelError, setCancelError] = useState<string | null>(null);

  function cancel(orderId: OrderId): void {
    setCancelError(null);
    startTransition(async () => {
      addOptimisticCancel(orderId);
      try {
        await cancelOrder(orderId);
      } catch (err: unknown) {
        setCancelError(toErrorMessage(err));
        return;
      }
      // Refetch inside the Action, so the optimistic row stays until the fresh list has arrived.
      // A failed refetch surfaces through `use()` and the error boundary on the next render.
      invalidateOrders(accountId);
      const refreshed = ordersResource(accountId);
      await refreshed.catch(() => undefined);
      // State updates after an `await` need their own transition to stay part of the Action.
      startTransition(() => {
        setOrdersPromise(refreshed);
      });
    });
  }

  return { orders, cancel, cancelError };
}

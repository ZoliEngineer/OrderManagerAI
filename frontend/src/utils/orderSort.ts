import type { Order } from '../types/order';

/** The sortable subset of `Order` keys. `Extract` fails to compile if a key is misspelled or removed. */
export type SortColumn = Extract<
  keyof Order,
  'createdAt' | 'ticker' | 'side' | 'type' | 'quantity' | 'limitPrice' | 'filledQuantity' | 'avgFillPrice' | 'status'
>;

export type SortDirection = 'asc' | 'desc';

export interface SortState {
  readonly column: SortColumn;
  readonly direction: SortDirection;
}

export const DEFAULT_SORT: SortState = { column: 'createdAt', direction: 'desc' };

type Comparator<T> = (a: T, b: T) => number;

const byText =
  (get: (order: Order) => string): Comparator<Order> =>
  (a, b) =>
    get(a).localeCompare(get(b));

const byNumber =
  (get: (order: Order) => number | null): Comparator<Order> =>
  (a, b) =>
    (get(a) ?? 0) - (get(b) ?? 0);

/**
 * One comparator per column. `Record<K, V>` is a mapped type (`{ [P in K]: V }`), so the object must be
 * exhaustive: adding a column to `SortColumn` without a comparator here is a compile error (the JS
 * version silently fell through to `default`).
 */
const COMPARATORS: Readonly<Record<SortColumn, Comparator<Order>>> = {
  createdAt: byNumber((o) => Date.parse(o.createdAt)),
  ticker: byText((o) => o.ticker),
  side: byText((o) => o.side),
  type: byText((o) => o.type),
  status: byText((o) => o.status),
  quantity: byNumber((o) => o.quantity),
  limitPrice: byNumber((o) => o.limitPrice),
  filledQuantity: byNumber((o) => o.filledQuantity),
  avgFillPrice: byNumber((o) => o.avgFillPrice),
};

/** Returns a new array. `readonly Order[]` has no `sort()`, which mutates; `toSorted()` copies. */
export function sortOrders(orders: readonly Order[], { column, direction }: SortState): Order[] {
  const compare = COMPARATORS[column];
  const sign = direction === 'asc' ? 1 : -1;
  return orders.toSorted((a, b) => sign * compare(a, b));
}

/** Clicking the active column flips direction; clicking another column sorts it ascending. */
export function nextSort(current: SortState, column: SortColumn): SortState {
  if (column !== current.column) return { column, direction: 'asc' };
  return { column, direction: current.direction === 'asc' ? 'desc' : 'asc' };
}

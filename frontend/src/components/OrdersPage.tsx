import { useState } from 'react';
import { useOrders } from '../hooks/useOrders';
import type { AccountId } from '../types/account';
import { ORDER_STATUSES, type Order, type OrderId, type OrderStatus } from '../types/order';
import { formatPrice, formatQuantity, formatTimestamp } from '../utils/format';
import { DEFAULT_SORT, nextSort, sortOrders, type SortColumn, type SortState } from '../utils/orderSort';
import '../styles/OrdersPage.css';

/** `Record<OrderStatus, …>` is exhaustive: a new status without a label does not compile. */
const STATUS_LABEL: Readonly<Record<OrderStatus, string>> = {
  NEW: 'NEW',
  PENDING: 'PENDING',
  PARTIALLY_FILLED: 'PARTIAL',
  FILLED: 'FILLED',
  CANCELLED: 'CANCELLED',
  REJECTED: 'REJECTED',
};

const CANCELLABLE: ReadonlySet<OrderStatus> = new Set<OrderStatus>(['NEW', 'PENDING', 'PARTIALLY_FILLED']);

/** 'PARTIALLY_FILLED' → 'partially-filled', matching the CSS modifiers. */
const statusModifier = (status: OrderStatus): string => status.toLowerCase().replaceAll('_', '-');

interface ColumnDef {
  readonly column: SortColumn;
  readonly label: string;
  readonly numeric?: true;
}

const COLUMNS: readonly ColumnDef[] = [
  { column: 'createdAt', label: 'Time' },
  { column: 'ticker', label: 'Ticker' },
  { column: 'side', label: 'Side' },
  { column: 'type', label: 'Type' },
  { column: 'quantity', label: 'Qty', numeric: true },
  { column: 'limitPrice', label: 'Limit', numeric: true },
  { column: 'filledQuantity', label: 'Filled', numeric: true },
  { column: 'avgFillPrice', label: 'Avg Price', numeric: true },
  { column: 'status', label: 'Status' },
];

function StatusBadge({ status }: { readonly status: OrderStatus }) {
  return <span className={`order-status order-status--${statusModifier(status)}`}>{STATUS_LABEL[status]}</span>;
}

interface SortHeaderProps extends ColumnDef {
  readonly sort: SortState;
  readonly onSort: (column: SortColumn) => void;
}

function SortHeader({ column, label, numeric, sort, onSort }: SortHeaderProps) {
  const active = column === sort.column;
  const indicator = active ? (sort.direction === 'asc' ? ' ▲' : ' ▼') : '';
  const className = ['sortable', active && 'sort-active', numeric && 'col-num'].filter(Boolean).join(' ');
  return (
    <th
      className={className}
      aria-sort={active ? (sort.direction === 'asc' ? 'ascending' : 'descending') : undefined}
      onClick={() => {
        onSort(column);
      }}
    >
      {label}
      {indicator}
    </th>
  );
}

interface OrderRowProps {
  readonly order: Order;
  readonly cancelling: boolean;
  readonly onCancel: (orderId: OrderId) => void;
}

function OrderRow({ order, cancelling, onCancel }: OrderRowProps) {
  return (
    <tr className={`order-row order-row--${statusModifier(order.status)}`}>
      <td className="col-time">{formatTimestamp(order.createdAt)}</td>
      <td>
        <span className="order-ticker">{order.ticker}</span>
      </td>
      <td>
        <span className={`order-side order-side--${order.side.toLowerCase()}`}>{order.side}</span>
      </td>
      <td className="col-type">{order.type}</td>
      <td className="col-num">{formatQuantity(order.quantity)}</td>
      <td className="col-num">{order.type === 'LIMIT' ? formatPrice(order.limitPrice) : '—'}</td>
      <td className="col-num">{formatQuantity(order.filledQuantity)}</td>
      <td className="col-num">{formatPrice(order.avgFillPrice)}</td>
      <td>
        <StatusBadge status={order.status} />
        {order.rejectionReason && (
          <span className="order-rejection" title={order.rejectionReason}>
            ⓘ
          </span>
        )}
      </td>
      <td className="col-action">
        {CANCELLABLE.has(order.status) && (
          <button
            className="btn-cancel"
            disabled={cancelling}
            onClick={() => {
              onCancel(order.id);
            }}
          >
            {cancelling ? '…' : 'Cancel'}
          </button>
        )}
      </td>
    </tr>
  );
}

interface OrdersPageProps {
  readonly selectedAccountId: AccountId | null;
}

export function OrdersPage({ selectedAccountId }: OrdersPageProps) {
  const { orders: ordersState, cancel, cancelling, cancelError } = useOrders(selectedAccountId);
  const [sort, setSort] = useState<SortState>(DEFAULT_SORT);
  const [hiddenStatuses, setHiddenStatuses] = useState<ReadonlySet<OrderStatus>>(new Set());

  const orders: readonly Order[] = ordersState.status === 'success' ? ordersState.data : [];
  // Iterating the `as const` tuple gives a lifecycle order (NEW → … → REJECTED) instead of alphabetical.
  const presentStatuses = ORDER_STATUSES.filter((status) => orders.some((o) => o.status === status));
  const visible = sortOrders(
    orders.filter((o) => !hiddenStatuses.has(o.status)),
    sort,
  );

  function toggleStatus(status: OrderStatus): void {
    setHiddenStatuses((prev) => {
      const next = new Set(prev);
      if (!next.delete(status)) next.add(status);
      return next;
    });
  }

  function handleSort(column: SortColumn): void {
    setSort((prev) => nextSort(prev, column));
  }

  function renderMessage() {
    switch (ordersState.status) {
      case 'idle':
        return <p className="orders-empty">Select an account to view orders.</p>;
      case 'loading':
        return <p className="orders-loading">Loading…</p>;
      case 'error':
        return <p className="orders-error">Failed to load orders: {ordersState.error}</p>;
      case 'success':
        if (orders.length === 0) return <p className="orders-empty">No orders found for this account.</p>;
        if (visible.length === 0) return <p className="orders-empty">No orders match the selected filters.</p>;
        return null;
    }
  }

  return (
    <div className="orders-page">
      <div className="page-header">
        <h1 className="page-title">Orders</h1>
        {ordersState.status === 'success' && (
          <span className="page-subtitle">
            {orders.length} order{orders.length === 1 ? '' : 's'}
          </span>
        )}
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">Order Blotter</span>
        </div>

        {presentStatuses.length > 0 && (
          <div className="orders-filters">
            {presentStatuses.map((status) => (
              <button
                key={status}
                className={`order-status order-status--${statusModifier(status)}${hiddenStatuses.has(status) ? ' filter-inactive' : ''}`}
                aria-pressed={!hiddenStatuses.has(status)}
                onClick={() => {
                  toggleStatus(status);
                }}
              >
                {STATUS_LABEL[status]}
              </button>
            ))}
            {hiddenStatuses.size > 0 && (
              <button
                className="filter-clear"
                onClick={() => {
                  setHiddenStatuses(new Set());
                }}
              >
                Clear
              </button>
            )}
          </div>
        )}

        {cancelError && <p className="orders-error">Cancel failed: {cancelError}</p>}
        {renderMessage()}

        {visible.length > 0 && (
          <div className="orders-table-wrap">
            <table className="orders-table">
              <thead>
                <tr>
                  {COLUMNS.map((def) => (
                    <SortHeader key={def.column} {...def} sort={sort} onSort={handleSort} />
                  ))}
                  <th />
                </tr>
              </thead>
              <tbody>
                {visible.map((order) => (
                  <OrderRow key={order.id} order={order} cancelling={cancelling.has(order.id)} onCancel={cancel} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

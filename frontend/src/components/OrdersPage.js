import { useState } from 'react';
import useOrders from '../hooks/useOrders';
import '../styles/OrdersPage.css';

const STATUS_LABEL = {
  PARTIALLY_FILLED: 'PARTIAL',
};

function formatTime(timestamp) {
  if (!timestamp) return '—';
  return new Intl.DateTimeFormat('en-US', {
    month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
    hour12: false,
  }).format(new Date(timestamp));
}

function formatQty(value) {
  if (value == null) return '—';
  return Number(value).toLocaleString('en-US', { maximumFractionDigits: 4 });
}

function formatPrice(value) {
  if (value == null || Number(value) === 0) return '—';
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
}

function StatusBadge({ status }) {
  const label = STATUS_LABEL[status] ?? status;
  return (
    <span className={`order-status order-status--${status.toLowerCase().replace('_', '-')}`}>
      {label}
    </span>
  );
}

function getSortValue(order, col) {
  switch (col) {
    case 'createdAt':       return order.createdAt ?? 0;
    case 'ticker':          return order.ticker ?? '';
    case 'side':            return order.side ?? '';
    case 'type':            return order.type ?? '';
    case 'quantity':        return Number(order.quantity) || 0;
    case 'limitPrice':      return Number(order.limitPrice) || 0;
    case 'filledQuantity':  return Number(order.filledQuantity) || 0;
    case 'avgFillPrice':    return Number(order.avgFillPrice) || 0;
    case 'status':          return order.status ?? '';
    default:                return '';
  }
}

function SortTh({ col, label, sortCol, sortDir, onSort, className }) {
  const active = col === sortCol;
  const indicator = active ? (sortDir === 'asc' ? ' ▲' : ' ▼') : '';
  return (
    <th
      className={`sortable${active ? ' sort-active' : ''}${className ? ` ${className}` : ''}`}
      onClick={() => onSort(col)}
    >
      {label}{indicator}
    </th>
  );
}

const CANCELLABLE = new Set(['NEW', 'PENDING', 'PARTIALLY_FILLED']);

export default function OrdersPage({ selectedAccountId }) {
  const { orders, loading, error, cancel, cancelling } = useOrders(selectedAccountId);
  const [sortCol, setSortCol] = useState('createdAt');
  const [sortDir, setSortDir] = useState('desc');
  const [hiddenStatuses, setHiddenStatuses] = useState(new Set());

  function handleSort(col) {
    if (col === sortCol) {
      setSortDir(d => d === 'asc' ? 'desc' : 'asc');
    } else {
      setSortCol(col);
      setSortDir('asc');
    }
  }

  function toggleStatus(status) {
    setHiddenStatuses(prev => {
      const next = new Set(prev);
      next.has(status) ? next.delete(status) : next.add(status);
      return next;
    });
  }

  const presentStatuses = [...new Set(orders.map(o => o.status))].sort();

  const filtered = hiddenStatuses.size === 0
    ? orders
    : orders.filter(o => !hiddenStatuses.has(o.status));

  const sorted = [...filtered].sort((a, b) => {
    const av = getSortValue(a, sortCol);
    const bv = getSortValue(b, sortCol);
    const cmp = typeof av === 'string' ? av.localeCompare(bv) : av - bv;
    return sortDir === 'asc' ? cmp : -cmp;
  });

  const thProps = { sortCol, sortDir, onSort: handleSort };

  return (
    <div className="orders-page">
      <div className="page-header">
        <h1 className="page-title">Orders</h1>
        {selectedAccountId && !loading && (
          <span className="page-subtitle">{orders.length} order{orders.length !== 1 ? 's' : ''}</span>
        )}
      </div>

      <div className="card">
        <div className="card-header">
          <span className="card-title">Order Blotter</span>
        </div>

        {presentStatuses.length > 0 && (
          <div className="orders-filters">
            {presentStatuses.map(status => {
              const active = !hiddenStatuses.has(status);
              const label = STATUS_LABEL[status] ?? status;
              return (
                <button
                  key={status}
                  className={`order-status order-status--${status.toLowerCase().replace('_', '-')}${active ? '' : ' filter-inactive'}`}
                  onClick={() => toggleStatus(status)}
                >
                  {label}
                </button>
              );
            })}
            {hiddenStatuses.size > 0 && (
              <button className="filter-clear" onClick={() => setHiddenStatuses(new Set())}>
                Clear
              </button>
            )}
          </div>
        )}

        {!selectedAccountId && (
          <p className="orders-empty">Select an account to view orders.</p>
        )}

        {selectedAccountId && loading && (
          <p className="orders-loading">Loading…</p>
        )}

        {selectedAccountId && error && (
          <p className="orders-error">Failed to load orders: {error}</p>
        )}

        {selectedAccountId && !loading && !error && orders.length === 0 && (
          <p className="orders-empty">No orders found for this account.</p>
        )}

        {selectedAccountId && !loading && !error && orders.length > 0 && sorted.length === 0 && (
          <p className="orders-empty">No orders match the selected filters.</p>
        )}

        {sorted.length > 0 && (
          <div className="orders-table-wrap">
            <table className="orders-table">
              <thead>
                <tr>
                  <SortTh col="createdAt"      label="Time"      {...thProps} />
                  <SortTh col="ticker"          label="Ticker"    {...thProps} />
                  <SortTh col="side"            label="Side"      {...thProps} />
                  <SortTh col="type"            label="Type"      {...thProps} />
                  <SortTh col="quantity"        label="Qty"       {...thProps} className="col-num" />
                  <SortTh col="limitPrice"      label="Limit"     {...thProps} className="col-num" />
                  <SortTh col="filledQuantity"  label="Filled"    {...thProps} className="col-num" />
                  <SortTh col="avgFillPrice"    label="Avg Price" {...thProps} className="col-num" />
                  <SortTh col="status"          label="Status"    {...thProps} />
                  <th />
                </tr>
              </thead>
              <tbody>
                {sorted.map(order => (
                  <tr key={order.id} className={`order-row order-row--${order.status.toLowerCase().replace('_', '-')}`}>
                    <td className="col-time">{formatTime(order.createdAt)}</td>
                    <td><span className="order-ticker">{order.ticker}</span></td>
                    <td><span className={`order-side order-side--${order.side.toLowerCase()}`}>{order.side}</span></td>
                    <td className="col-type">{order.type}</td>
                    <td className="col-num">{formatQty(order.quantity)}</td>
                    <td className="col-num">{order.type === 'LIMIT' ? formatPrice(order.limitPrice) : '—'}</td>
                    <td className="col-num">{formatQty(order.filledQuantity)}</td>
                    <td className="col-num">{formatPrice(order.avgFillPrice)}</td>
                    <td>
                      <StatusBadge status={order.status} />
                      {order.rejectionReason && (
                        <span className="order-rejection" title={order.rejectionReason}>ⓘ</span>
                      )}
                    </td>
                    <td className="col-action">
                      {CANCELLABLE.has(order.status) && (
                        <button
                          className="btn-cancel"
                          disabled={cancelling.has(order.id)}
                          onClick={() => cancel(order.id)}
                        >
                          {cancelling.has(order.id) ? '…' : 'Cancel'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

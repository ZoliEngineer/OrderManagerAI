import { getToken } from './client';

const ORDERS_BASE_URL = process.env.REACT_APP_ORDERS_API_URL ?? '';

async function ordersRequest(path, options = {}) {
  const token = await getToken();
  const response = await fetch(`${ORDERS_BASE_URL}${path}`, {
    ...options,
    headers: {
      ...options.headers,
      Authorization: `Bearer ${token}`,
    },
  });
  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.message ?? `HTTP ${response.status}`);
  }
  return response.status === 204 ? null : response.json();
}

export async function getOrders(accountId) {
  return ordersRequest(`/api/orders?accountId=${accountId}`);
}

export async function placeOrder(req) {
  return ordersRequest('/api/orders', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
}

export async function cancelOrder(orderId) {
  return ordersRequest(`/api/orders/${orderId}`, { method: 'DELETE' });
}

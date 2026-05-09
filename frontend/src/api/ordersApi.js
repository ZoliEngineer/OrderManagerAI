import { getToken } from './client';

const ORDERS_BASE_URL = process.env.REACT_APP_ORDERS_API_URL ?? 'http://localhost:8082';

export async function placeOrder(req) {
  const token = await getToken();
  const response = await fetch(`${ORDERS_BASE_URL}/api/orders`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(req),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => ({}));
    throw new Error(err.message ?? `HTTP ${response.status}`);
  }
  return response.json();
}

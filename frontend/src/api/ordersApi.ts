import { getConfig } from '../config';
import type { AccountId } from '../types/account';
import type { Order, OrderId, OrderResponse, PlaceOrderRequest } from '../types/order';
import { jsonBody, request, requestNoContent } from './client';

const orders = <T>(path: string, init?: RequestInit): Promise<T> =>
  request<T>(getConfig().ordersApiUrl, path, init);

export const getOrders = (accountId: AccountId): Promise<Order[]> =>
  orders(`/api/orders?${new URLSearchParams({ accountId }).toString()}`);

export const placeOrder = (req: PlaceOrderRequest): Promise<OrderResponse> =>
  orders('/api/orders', { method: 'POST', ...jsonBody(req) });

export const cancelOrder = (orderId: OrderId): Promise<void> =>
  requestNoContent(getConfig().ordersApiUrl, `/api/orders/${encodeURIComponent(orderId)}`, { method: 'DELETE' });

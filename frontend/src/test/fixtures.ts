import { toAccountId } from '../types/account';
import type { MarketStatus } from '../types/marketData';
import { toOrderId, type Order } from '../types/order';

/** Typed test data: a fixture that drifts from the real interface fails to compile. */
export const marketStatus = (overrides: Partial<MarketStatus> = {}): MarketStatus => ({
  exchange: 'US',
  holiday: null,
  isOpen: true,
  session: 'regular',
  timezone: 'America/New_York',
  t: 0,
  ...overrides,
});

export const order = (overrides: Partial<Order> = {}): Order => ({
  id: toOrderId('order-1'),
  accountId: toAccountId('account-1'),
  ticker: 'AAPL',
  side: 'BUY',
  type: 'MARKET',
  quantity: 10,
  limitPrice: null,
  filledQuantity: null,
  avgFillPrice: null,
  status: 'NEW',
  rejectionReason: null,
  createdAt: '2026-11-18T10:00:00Z',
  ...overrides,
});

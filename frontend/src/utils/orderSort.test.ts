import { describe, expect, test } from 'vitest';
import { toOrderId, type OrderId } from '../types/order';
import { order } from '../test/fixtures';
import { DEFAULT_SORT, nextSort, sortOrders } from './orderSort';

const orders = [
  order({ id: toOrderId('a'), ticker: 'MSFT', limitPrice: 300, createdAt: '2026-11-18T09:00:00Z' }),
  order({ id: toOrderId('b'), ticker: 'AAPL', limitPrice: null, createdAt: '2026-11-18T11:00:00Z' }),
  order({ id: toOrderId('c'), ticker: 'NVDA', limitPrice: 100, createdAt: '2026-11-18T10:00:00Z' }),
] as const;

const ids = (list: readonly { id: OrderId }[]) => list.map((o) => o.id);

describe('sortOrders', () => {
  test('defaults to newest first', () => {
    expect(ids(sortOrders(orders, DEFAULT_SORT))).toEqual(['b', 'c', 'a']);
  });

  test('sorts text columns alphabetically', () => {
    expect(ids(sortOrders(orders, { column: 'ticker', direction: 'asc' }))).toEqual(['b', 'a', 'c']);
  });

  test('treats null numbers as zero', () => {
    expect(ids(sortOrders(orders, { column: 'limitPrice', direction: 'asc' }))).toEqual(['b', 'c', 'a']);
  });

  test('does not mutate its input', () => {
    const before = ids(orders);
    sortOrders(orders, { column: 'ticker', direction: 'desc' });
    expect(ids(orders)).toEqual(before);
  });
});

describe('nextSort', () => {
  test('flips direction on the active column', () => {
    expect(nextSort({ column: 'ticker', direction: 'asc' }, 'ticker')).toEqual({ column: 'ticker', direction: 'desc' });
  });

  test('starts ascending on a new column', () => {
    expect(nextSort(DEFAULT_SORT, 'quantity')).toEqual({ column: 'quantity', direction: 'asc' });
  });
});

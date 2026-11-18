import type { AccountId } from './account';
import type { Brand } from './brand';

export type OrderId = Brand<string, 'OrderId'>;

export const toOrderId = (value: string): OrderId => value as OrderId;

/*
 * `as const` arrays are the single source of truth: they exist at runtime (for iteration, validation,
 * rendering) and the union type is derived from them, so the two can never drift apart.
 */
export const ORDER_SIDES = ['BUY', 'SELL'] as const;
export type OrderSide = (typeof ORDER_SIDES)[number];

export const ORDER_TYPES = ['MARKET', 'LIMIT'] as const;
export type OrderType = (typeof ORDER_TYPES)[number];

export const ORDER_STATUSES = ['NEW', 'PENDING', 'PARTIALLY_FILLED', 'FILLED', 'CANCELLED', 'REJECTED'] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];

/** Mirrors the `Order` entity in orderservice. */
export interface Order {
  readonly id: OrderId;
  readonly accountId: AccountId;
  readonly ticker: string;
  readonly side: OrderSide;
  readonly type: OrderType;
  readonly quantity: number;
  readonly limitPrice: number | null;
  readonly filledQuantity: number | null;
  readonly avgFillPrice: number | null;
  readonly status: OrderStatus;
  readonly rejectionReason: string | null;
  /** ISO-8601 timestamp. */
  readonly createdAt: string;
}

export interface OrderRequestBase {
  readonly accountId: AccountId;
  readonly ticker: string;
  readonly side: OrderSide;
  readonly quantity: number;
}

/**
 * A discriminated union on `type`: a market order cannot carry a limit price and a limit order
 * cannot be missing one. Invalid requests are unrepresentable, not just checked at runtime.
 */
export type PlaceOrderRequest =
  | (OrderRequestBase & { readonly type: 'MARKET' })
  | (OrderRequestBase & { readonly type: 'LIMIT'; readonly limitPrice: number });

/** Mirrors `OrderResponse` in orderservice. */
export interface OrderResponse {
  readonly orderId: OrderId;
  readonly status: OrderStatus;
  readonly message: string | null;
}

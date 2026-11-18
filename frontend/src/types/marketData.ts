/** Mirrors the `Stock` record in marketdata. Change fields are null until the source has a previous price. */
export interface Stock {
  readonly ticker: string;
  readonly name: string;
  readonly price: number;
  readonly openPrice: number | null;
  readonly highPrice: number | null;
  readonly lowPrice: number | null;
  readonly prevClose: number | null;
  readonly totalChange: number | null;
  readonly changePercent: number | null;
  readonly lastChange: number | null;
}

/** The subset of a stock the order ticket needs: `Pick` keeps it in sync with `Stock`. */
export type StockQuote = Pick<Stock, 'ticker' | 'name' | 'price'>;

export const MARKET_SESSIONS = ['pre-market', 'regular', 'post-market'] as const;
export type MarketSession = (typeof MARKET_SESSIONS)[number];

/** Mirrors `MarketStatus` in marketdata (Finnhub's market-status payload). */
export interface MarketStatus {
  readonly exchange: string;
  readonly holiday: string | null;
  readonly isOpen: boolean;
  /** Null when the market is closed for the day. */
  readonly session: MarketSession | null;
  readonly timezone: string;
  readonly t: number;
}

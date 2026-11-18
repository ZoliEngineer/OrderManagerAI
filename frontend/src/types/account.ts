import type { Brand } from './brand';

export type AccountId = Brand<string, 'AccountId'>;

/** Ids arrive as plain strings from the API and from `<select>` values; this is the one place they get branded. */
export const toAccountId = (value: string): AccountId => value as AccountId;

/** Mirrors `AccountSummary` in accountservice. */
export interface AccountSummary {
  readonly id: AccountId;
  readonly displayName: string;
}

/** Mirrors `AccountDetails` in accountservice. Java `BigDecimal` is serialized as a JSON number. */
export interface AccountDetails extends AccountSummary {
  readonly cashBalance: number;
  readonly buyingPower: number;
}

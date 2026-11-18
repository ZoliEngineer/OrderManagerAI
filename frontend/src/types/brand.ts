/**
 * Nominal ("branded") typing on top of TypeScript's structural type system.
 *
 * Every id in this app is a UUID string, so plain `string` would let an order id be passed where an
 * account id is expected. The phantom `__brand` property exists only at compile time and makes the two
 * incompatible. A value becomes branded only by going through a single, explicit conversion point.
 */
declare const brand: unique symbol;

export type Brand<T, Name extends string> = T & { readonly [brand]: Name };

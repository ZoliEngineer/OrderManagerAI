/**
 * Anything can be thrown in JavaScript, so `catch (err)` gives `unknown` (with `strict`), not `Error`.
 * Narrow it instead of asserting.
 */
export function toErrorMessage(err: unknown): string {
  if (err instanceof Error) return err.message;
  if (typeof err === 'string') return err;
  return 'Unexpected error';
}

/** Exhaustiveness check: only compiles if every case of a union has been handled before reaching it. */
export function assertNever(value: never): never {
  throw new Error(`Unhandled value: ${JSON.stringify(value)}`);
}

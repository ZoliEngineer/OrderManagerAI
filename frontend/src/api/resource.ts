/**
 * A keyed promise cache for React 19's `use()`.
 *
 * `use(promise)` suspends until the promise settles, but it needs the *same* promise on every render:
 * a promise created during render would be new each time and suspend forever. This module owns the
 * promises, so components can simply write `use(ordersResource(accountId))`.
 *
 * - A pending or fulfilled promise is reused until `invalidate(key)` drops it (e.g. after a mutation).
 * - A rejected promise stays cached, so the error boundary shows the error instead of React retrying in
 *   a loop. The boundary's "Retry" calls `retryFailed()`, which drops failed entries so the next render
 *   fetches again.
 */
const cache = new Map<string, Promise<unknown>>();
const failed = new Set<string>();

export function cached<T>(key: string, load: () => Promise<T>): Promise<T> {
  const hit = cache.get(key);
  // The key determines the type: each key is only ever written by one typed resource function.
  if (hit) return hit as Promise<T>;

  const promise = load();
  cache.set(key, promise);
  promise.catch(() => {
    if (cache.get(key) === promise) failed.add(key);
  });
  return promise;
}

/** Drops one entry; the next `cached()` call for the key loads again. */
export function invalidate(key: string): void {
  cache.delete(key);
  failed.delete(key);
}

/** Drops every entry whose promise rejected. Called by error boundaries before they re-render. */
export function retryFailed(): void {
  for (const key of failed) cache.delete(key);
  failed.clear();
}

/** Test helper: an empty cache per test. */
export function clearCache(): void {
  cache.clear();
  failed.clear();
}

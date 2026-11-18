/**
 * The state of one asynchronous load, as a discriminated union on `status`.
 *
 * Compared to three independent `data` / `loading` / `error` variables, impossible combinations
 * ("loading with an error", "success without data") cannot be expressed, and a `switch` on `status`
 * narrows the type, so `data` is only reachable in the `success` branch.
 */
export type AsyncState<T> =
  | { readonly status: 'idle' }
  | { readonly status: 'loading' }
  | { readonly status: 'success'; readonly data: T }
  | { readonly status: 'error'; readonly error: string };

export type AsyncStatus = AsyncState<unknown>['status'];

export const IDLE = { status: 'idle' } as const satisfies AsyncState<never>;
export const LOADING = { status: 'loading' } as const satisfies AsyncState<never>;

export const success = <T>(data: T): AsyncState<T> => ({ status: 'success', data });
export const failure = (error: string): AsyncState<never> => ({ status: 'error', error });

import { Component, Suspense, type ReactNode } from 'react';
import { retryFailed } from '../api/resource';
import { toErrorMessage } from '../utils/errors';

interface ErrorBoundaryProps {
  readonly children: ReactNode;
  readonly fallback: (error: unknown, reset: () => void) => ReactNode;
  /** Runs before the boundary re-renders its children, e.g. to drop failed cache entries. */
  readonly onReset?: () => void;
}

interface ErrorBoundaryState {
  readonly hasError: boolean;
  readonly error: unknown;
}

/**
 * Catches errors thrown while rendering its children, including promises passed to `use()` that reject.
 * It is a class because React still has no hook equivalent of `getDerivedStateFromError`.
 */
export class ErrorBoundary extends Component<ErrorBoundaryProps, ErrorBoundaryState> {
  override state: ErrorBoundaryState = { hasError: false, error: null };

  static getDerivedStateFromError(error: unknown): ErrorBoundaryState {
    return { hasError: true, error };
  }

  private readonly reset = (): void => {
    this.props.onReset?.();
    this.setState({ hasError: false, error: null });
  };

  override render(): ReactNode {
    return this.state.hasError ? this.props.fallback(this.state.error, this.reset) : this.props.children;
  }
}

interface DataBoundaryProps {
  readonly children: ReactNode;
  /** Shown while a child is suspended on `use()`. */
  readonly loading: ReactNode;
  /** Shown when a child's data failed to load; `retry` refetches. */
  readonly error: (message: string, retry: () => void) => ReactNode;
}

/** Suspense + an error boundary whose retry refetches failed resources: the usual wrapper around `use()`. */
export function DataBoundary({ children, loading, error }: DataBoundaryProps) {
  return (
    <ErrorBoundary onReset={retryFailed} fallback={(err, reset) => error(toErrorMessage(err), reset)}>
      <Suspense fallback={loading}>{children}</Suspense>
    </ErrorBoundary>
  );
}

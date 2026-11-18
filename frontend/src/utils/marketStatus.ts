import type { AsyncState } from '../hooks/asyncState';
import type { MarketStatus } from '../types/marketData';
import { assertNever } from './errors';

function sessionLabel({ isOpen, session, holiday }: MarketStatus): string {
  if (isOpen) return 'Market Open';
  switch (session) {
    case 'pre-market':
      return 'Pre-Market';
    case 'post-market':
      return 'After Hours';
    case 'regular':
    case null:
      return holiday ? `Closed — ${holiday}` : 'Market Closed';
    default:
      // If a new MarketSession is added, this line stops compiling until it is handled above.
      return assertNever(session);
  }
}

export function marketStatusLabel(state: AsyncState<MarketStatus>): string {
  switch (state.status) {
    case 'idle':
    case 'loading':
      return 'Market …';
    case 'error':
      return 'Market status unavailable';
    case 'success':
      // Narrowed: `state.data` exists only on the success variant.
      return sessionLabel(state.data);
    default:
      return assertNever(state);
  }
}

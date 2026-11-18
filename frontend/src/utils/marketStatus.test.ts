import { describe, expect, test } from 'vitest';
import { IDLE, LOADING, failure, success } from '../hooks/asyncState';
import { marketStatus } from '../test/fixtures';
import { marketStatusLabel } from './marketStatus';

describe('marketStatusLabel', () => {
  test.each([
    ['open', marketStatus({ isOpen: true }), 'Market Open'],
    ['pre-market', marketStatus({ isOpen: false, session: 'pre-market' }), 'Pre-Market'],
    ['after hours', marketStatus({ isOpen: false, session: 'post-market' }), 'After Hours'],
    ['holiday', marketStatus({ isOpen: false, session: null, holiday: 'Thanksgiving' }), 'Closed — Thanksgiving'],
    ['closed', marketStatus({ isOpen: false, session: null }), 'Market Closed'],
  ])('%s', (_name, status, expected) => {
    expect(marketStatusLabel(success(status))).toBe(expected);
  });

  test('pending and failed loads', () => {
    expect(marketStatusLabel(IDLE)).toBe('Market …');
    expect(marketStatusLabel(LOADING)).toBe('Market …');
    expect(marketStatusLabel(failure('boom'))).toBe('Market status unavailable');
  });
});

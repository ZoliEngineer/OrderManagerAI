import { render, screen } from '@testing-library/react';
import type { useAuth } from 'react-oidc-context';
import { beforeEach, expect, test, vi } from 'vitest';
import { success, type AsyncState } from './hooks/asyncState';
import type { Stock } from './types/marketData';
import { marketStatus } from './test/fixtures';
import { App } from './App';

// vi.mock calls are hoisted above the imports, so App sees the mocked modules.
vi.mock('react-oidc-context', () => ({
  useAuth: (): Partial<ReturnType<typeof useAuth>> => ({
    isLoading: false,
    isAuthenticated: true,
    signinRedirect: vi.fn(),
  }),
}));

vi.mock('./auth/oidc', () => ({
  getAccessToken: vi.fn(() => Promise.resolve('test-token')),
  signOut: vi.fn(),
}));

vi.mock('./hooks/useMarketData', () => ({
  useMarketData: (): AsyncState<readonly Stock[]> => success([]),
}));

vi.mock('./hooks/useMarketStatus', () => ({
  useMarketStatus: () => success(marketStatus({ isOpen: true })),
}));

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(Response.json([]));
});

test('renders app shell with navigation and market data page', async () => {
  render(<App />);

  expect(await screen.findByRole('heading', { name: 'Market Data' })).toBeInTheDocument();
  expect(screen.getByText('Market Open')).toBeInTheDocument();
  expect(screen.getByText('Equities')).toBeInTheDocument();
  expect(await screen.findByRole('option', { name: 'Select account' })).toBeInTheDocument();
});

import { screen } from '@testing-library/react';
import type { useAuth } from 'react-oidc-context';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { beforeEach, expect, test, vi } from 'vitest';
import { success, type AsyncState } from './hooks/asyncState';
import type { Stock } from './types/marketData';
import { marketStatus, order } from './test/fixtures';
import { routes } from './routes';
import { renderAsync } from './test/render';

// vi.mock calls are hoisted above the imports, so App sees the mocked modules.
vi.mock('react-oidc-context', () => ({
  useAuth: (): Partial<ReturnType<typeof useAuth>> => ({
    isLoading: false,
    isAuthenticated: true,
  }),
}));

vi.mock('./auth/oidc', () => ({
  getAccessToken: vi.fn(() => Promise.resolve('test-token')),
  signIn: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock('./hooks/useMarketData', () => ({
  useMarketData: (): AsyncState<readonly Stock[]> => success([]),
}));

vi.mock('./hooks/useMarketStatus', () => ({
  useMarketStatus: () => success(marketStatus({ isOpen: true })),
}));

const ACCOUNTS = [{ id: 'account-1', displayName: 'Growth Fund' }];

beforeEach(() => {
  vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    if (url.includes('/api/accounts')) return Promise.resolve(Response.json(ACCOUNTS));
    if (url.includes('/api/orders')) return Promise.resolve(Response.json([order()]));
    return Promise.resolve(Response.json([]));
  });
});

async function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  await renderAsync(<RouterProvider router={router} />);
  return router;
}

test('redirects / to the market page and renders the app shell', async () => {
  const router = await renderAt('/');

  expect(await screen.findByRole('heading', { name: 'Market Data' })).toBeInTheDocument();
  expect(router.state.location.pathname).toBe('/market');
  expect(screen.getByText('Market Open')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /Market Data/ })).toHaveAttribute('aria-current', 'page');
  expect(await screen.findByRole('option', { name: 'Select account' })).toBeInTheDocument();
  expect(document.title).toBe('Market Data · OrderManager');
});

test('a deep link selects the page and the account, and nav links keep the account', async () => {
  await renderAt('/orders?account=account-1');

  expect(await screen.findByText('1 order')).toBeInTheDocument();
  expect(screen.getByRole('combobox', { name: 'Account' })).toHaveValue('account-1');
  expect(screen.getByRole('link', { name: /Portfolio/ })).toHaveAttribute('href', '/portfolio?account=account-1');
  expect(document.title).toBe('Orders · OrderManager');
});

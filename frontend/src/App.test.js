import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';

jest.mock('react-oidc-context', () => ({
  AuthProvider: ({ children }) => children,
  useAuth: () => ({ isLoading: false, isAuthenticated: true, signinRedirect: jest.fn() }),
}));

jest.mock('./auth/oidc', () => ({
  getUserManager: jest.fn(),
  signOut: jest.fn(),
  onSigninCallback: jest.fn(),
}));

jest.mock('./hooks/useMarketData', () => () => ({ stocks: [], loading: false, error: null }));
jest.mock('./hooks/useMarketStatus', () => () => ({
  status: { isOpen: true, session: 'regular', holiday: null },
  loading: false,
  error: null,
}));

import App from './App';

beforeEach(() => {
  global.fetch = jest.fn().mockResolvedValue({
    ok: true,
    json: () => Promise.resolve([]),
  });
});

afterEach(() => {
  jest.restoreAllMocks();
});

test('renders app shell with navigation and market data page', async () => {
  render(<App />);
  await waitFor(() => {
    expect(screen.getByRole('heading', { name: 'Market Data' })).toBeInTheDocument();
    expect(screen.getByText('Market Open')).toBeInTheDocument();
    expect(screen.getByText('Equities')).toBeInTheDocument();
  });
});

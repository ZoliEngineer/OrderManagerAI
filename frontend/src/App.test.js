import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';

jest.mock('@azure/msal-browser', () => ({
  PublicClientApplication: jest.fn().mockImplementation(() => ({
    initialize: jest.fn().mockResolvedValue(undefined),
    handleRedirectPromise: jest.fn().mockResolvedValue(null),
    addEventCallback: jest.fn().mockReturnValue('id'),
    removeEventCallback: jest.fn(),
    getActiveAccount: jest.fn().mockReturnValue(null),
    getAllAccounts: jest.fn().mockReturnValue([]),
    loginRedirect: jest.fn(),
    logoutRedirect: jest.fn(),
  })),
  LogLevel: { Error: 0, Warning: 1, Info: 2, Verbose: 3 },
  InteractionStatus: { None: 'none' },
}));

jest.mock('@azure/msal-react', () => ({
  MsalProvider: ({ children }) => children,
  useIsAuthenticated: () => true,
  useMsal: () => ({
    instance: { loginRedirect: jest.fn(), logoutRedirect: jest.fn() },
    inProgress: 'none',
  }),
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

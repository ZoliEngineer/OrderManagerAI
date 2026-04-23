import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
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

import { render, screen, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import App from './App';

beforeEach(() => {
  global.fetch = jest.fn();
});

afterEach(() => {
  jest.restoreAllMocks();
});

test('renders hello USA message from API', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    text: () => Promise.resolve('Hello USA'),
  });

  render(<App />);
  await waitFor(() => {
    expect(screen.getByText('Hello USA')).toBeInTheDocument();
  });
});

test('renders hello world message from API', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    text: () => Promise.resolve('Hello World'),
  });

  render(<App />);
  await waitFor(() => {
    expect(screen.getByText('Hello World')).toBeInTheDocument();
  });
});

test('renders error on API failure', async () => {
  fetch.mockResolvedValueOnce({ ok: false, status: 500 });

  render(<App />);
  await waitFor(() => {
    expect(screen.getByText(/Error/)).toBeInTheDocument();
  });
});

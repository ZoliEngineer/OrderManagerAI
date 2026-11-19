import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { expect, test, vi } from 'vitest';
import { order } from '../test/fixtures';
import { renderAsync } from '../test/render';
import type { Order } from '../types/order';
import { OrdersPage } from './OrdersPage';

vi.mock('../auth/oidc', () => ({
  getAccessToken: vi.fn(() => Promise.resolve('test-token')),
}));

interface Deferred {
  readonly promise: Promise<Response>;
  readonly resolve: (response: Response) => void;
}

function deferred(): Deferred {
  const { promise, resolve } = Promise.withResolvers<Response>();
  return { promise, resolve };
}

/** GET /api/orders answers from `lists` in turn; DELETE waits for the test to settle it. */
function mockOrdersApi(lists: readonly (readonly Order[])[], cancelResponse: Deferred) {
  let call = 0;
  return vi.spyOn(globalThis, 'fetch').mockImplementation((_input, init) => {
    if (init?.method === 'DELETE') return cancelResponse.promise;
    const list = lists[Math.min(call++, lists.length - 1)] ?? [];
    return Promise.resolve(Response.json(list));
  });
}

async function renderOrders() {
  const router = createMemoryRouter([{ path: '/orders', element: <OrdersPage /> }], {
    initialEntries: ['/orders?account=account-1'],
  });
  await renderAsync(<RouterProvider router={router} />);
}

const statusCell = (row: HTMLElement) => within(row).getAllByRole('cell')[8];

test('a cancel shows at once and is confirmed by the refetched list', async () => {
  const cancel = deferred();
  mockOrdersApi([[order({ status: 'NEW' })], [order({ status: 'CANCELLED' })]], cancel);
  await renderOrders();

  await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));

  // Optimistic: CANCELLED (and busy) before the server has answered.
  const row = screen.getAllByRole('row')[1];
  if (!row) throw new Error('order row missing');
  expect(statusCell(row)).toHaveTextContent('CANCELLED');
  expect(row).toHaveAttribute('aria-busy', 'true');
  expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();

  cancel.resolve(new Response(null, { status: 204 }));

  // Confirmed: still CANCELLED, no longer busy.
  await vi.waitFor(() => {
    expect(row).toHaveAttribute('aria-busy', 'false');
  });
  expect(statusCell(row)).toHaveTextContent('CANCELLED');
});

test('a failed cancel rolls back and shows the error', async () => {
  const cancel = deferred();
  mockOrdersApi([[order({ status: 'NEW' })]], cancel);
  await renderOrders();

  await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }));
  expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();

  cancel.resolve(Response.json({ message: 'Order already filled' }, { status: 409 }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Cancel failed: Order already filled');
  expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
});

test('a failed load shows the error with a retry that refetches', async () => {
  const fetchSpy = vi
    .spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(Response.json({ message: 'Service unavailable' }, { status: 503 }))
    .mockResolvedValueOnce(Response.json([order()]));
  await renderOrders();

  expect(await screen.findByText(/Failed to load orders: Service unavailable/)).toBeInTheDocument();
  // The retry re-renders a suspending tree, so it needs an awaited act scope (see test/render.tsx).
  const retry = screen.getByRole('button', { name: 'Retry' });
  await act(() => userEvent.click(retry));

  expect(await screen.findByText('1 order')).toBeInTheDocument();
  expect(fetchSpy).toHaveBeenCalledTimes(2);
});

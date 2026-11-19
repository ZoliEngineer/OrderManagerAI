import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { invalidateOrders, placeOrder } from '../api/ordersApi';
import { toAccountId } from '../types/account';
import { toOrderId } from '../types/order';
import { OrderTicketModal } from './OrderTicketModal';

vi.mock('../api/ordersApi', () => ({
  placeOrder: vi.fn(),
  invalidateOrders: vi.fn(),
}));

const ACCOUNT = toAccountId('account-1');
const STOCK = { ticker: 'AAPL', name: 'Apple Inc.', price: 190.5 };

function renderTicket(onClose = vi.fn()) {
  render(<OrderTicketModal stock={STOCK} accountId={ACCOUNT} onClose={onClose} />);
  return onClose;
}

test('opens as a modal dialog', () => {
  renderTicket();
  expect(screen.getByRole('dialog', { name: /AAPL/ })).toHaveAttribute('open');
});

test('rejects an invalid quantity without calling the API', async () => {
  renderTicket();

  await userEvent.click(screen.getByRole('button', { name: 'Place Order' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid quantity.');
  expect(placeOrder).not.toHaveBeenCalled();
});

test('places the order through the form Action and invalidates the blotter', async () => {
  const { promise, resolve } = Promise.withResolvers<Awaited<ReturnType<typeof placeOrder>>>();
  vi.mocked(placeOrder).mockReturnValue(promise);
  renderTicket();

  await userEvent.type(screen.getByLabelText('Quantity'), '10');
  await userEvent.click(screen.getByRole('button', { name: 'Place Order' }));

  // useFormStatus: the button shows the pending state while the Action runs.
  expect(await screen.findByRole('button', { name: 'Placing…' })).toBeDisabled();
  expect(placeOrder).toHaveBeenCalledWith({
    accountId: ACCOUNT,
    ticker: 'AAPL',
    side: 'BUY',
    type: 'MARKET',
    quantity: 10,
  });

  resolve({ orderId: toOrderId('order-1'), status: 'NEW', message: null });

  expect(await screen.findByRole('status')).toHaveTextContent('Order submitted.');
  expect(invalidateOrders).toHaveBeenCalledWith(ACCOUNT);
  // Rejected input is kept: the quantity is still there after the Action.
  expect(screen.getByLabelText('Quantity')).toHaveValue(10);
});

test('Esc closes the ticket', () => {
  const onClose = renderTicket();
  fireEvent(screen.getByRole('dialog'), new Event('cancel', { cancelable: true }));
  expect(onClose).toHaveBeenCalledOnce();
});

import { useActionState, useState, type MouseEvent, type SyntheticEvent } from 'react';
import { useFormStatus } from 'react-dom';
import { invalidateOrders, placeOrder } from '../api/ordersApi';
import type { AccountId } from '../types/account';
import type { StockQuote } from '../types/marketData';
import type { OrderRequestBase, OrderSide, OrderType, PlaceOrderRequest } from '../types/order';
import { toErrorMessage } from '../utils/errors';
import { formatCurrency } from '../utils/format';
import { ToggleGroup, type ToggleOption } from './ToggleGroup';
import './OrderTicketModal.css';

interface OrderTicketModalProps {
  readonly stock: StockQuote;
  readonly accountId: AccountId;
  readonly onClose: () => void;
}

/** The result of the last submit. "Submitting" is not a state here: `useActionState` tracks it as `isPending`. */
type TicketResult =
  | { readonly phase: 'editing' }
  | { readonly phase: 'submitted'; readonly message: string }
  | { readonly phase: 'failed'; readonly message: string };

const EDITING: TicketResult = { phase: 'editing' };

const SIDE_OPTIONS = [
  { value: 'BUY', label: 'BUY', className: 'buy' },
  { value: 'SELL', label: 'SELL', className: 'sell' },
] as const satisfies readonly ToggleOption<OrderSide>[];

const TYPE_OPTIONS = [
  { value: 'MARKET', label: 'Market' },
  { value: 'LIMIT', label: 'Limit' },
] as const satisfies readonly ToggleOption<OrderType>[];

/** Parses a numeric form field; `null` means invalid. Form inputs are strings until proven otherwise. */
function parsePositive(input: string): number | null {
  const value = Number(input);
  return input.trim() !== '' && Number.isFinite(value) && value > 0 ? value : null;
}

type BuildResult = { readonly ok: true; readonly request: PlaceOrderRequest } | { readonly ok: false; readonly error: string };

function buildRequest(
  base: Omit<OrderRequestBase, 'quantity'>,
  type: OrderType,
  quantityInput: string,
  limitPriceInput: string,
): BuildResult {
  const quantity = parsePositive(quantityInput);
  if (quantity === null) return { ok: false, error: 'Enter a valid quantity.' };
  if (type === 'MARKET') return { ok: true, request: { ...base, type, quantity } };

  const limitPrice = parsePositive(limitPriceInput);
  if (limitPrice === null) return { ok: false, error: 'Enter a valid limit price.' };
  return { ok: true, request: { ...base, type, quantity, limitPrice } };
}

/**
 * Opens the native <dialog> as a modal when it mounts: focus is trapped inside it, Esc fires `cancel`,
 * and the page behind becomes inert. React 19 runs the returned cleanup when the dialog unmounts.
 * Module-level, so the ref callback keeps one identity and does not reopen the dialog on every render.
 */
function showAsModal(dialog: HTMLDialogElement | null): (() => void) | undefined {
  if (!dialog) return undefined;
  dialog.showModal();
  return () => {
    dialog.close();
  };
}

/** `useFormStatus` reads the status of the enclosing <form>, so the button needs no props for it. */
function SubmitButton({ disabled }: { readonly disabled: boolean }) {
  const { pending } = useFormStatus();
  return (
    <button type="submit" className="btn-primary" disabled={disabled || pending}>
      {pending ? 'Placing…' : 'Place Order'}
    </button>
  );
}

export function OrderTicketModal({ stock, accountId, onClose }: OrderTicketModalProps) {
  const [side, setSide] = useState<OrderSide>('BUY');
  const [type, setType] = useState<OrderType>('MARKET');
  // Controlled inputs on purpose: React resets uncontrolled fields after a form Action, which would
  // wipe what the user typed when the order is rejected.
  const [quantity, setQuantity] = useState('');
  const [limitPrice, setLimitPrice] = useState(stock.price.toFixed(2));

  // A form Action: React calls it on submit inside a transition and tracks `isPending` for us.
  const [result, submitAction, isPending] = useActionState(async (): Promise<TicketResult> => {
    const built = buildRequest({ accountId, ticker: stock.ticker, side }, type, quantity, limitPrice);
    if (!built.ok) return { phase: 'failed', message: built.error };
    try {
      const response = await placeOrder(built.request);
      invalidateOrders(accountId);
      return { phase: 'submitted', message: response.message ?? 'Order submitted.' };
    } catch (err: unknown) {
      return { phase: 'failed', message: toErrorMessage(err) };
    }
  }, EDITING);

  const locked = isPending || result.phase === 'submitted';

  function selectType(next: OrderType): void {
    setType(next);
    setLimitPrice(stock.price.toFixed(2));
  }

  function handleCancel(event: SyntheticEvent<HTMLDialogElement>): void {
    // Esc: React owns the open state (the dialog closes by unmounting), and an order in flight stays open.
    event.preventDefault();
    if (!isPending) onClose();
  }

  function handleBackdropClick(event: MouseEvent<HTMLDialogElement>): void {
    // A click on the ::backdrop targets the <dialog> itself; clicks on the panel target its children.
    if (event.target === event.currentTarget && !isPending) onClose();
  }

  return (
    <dialog
      ref={showAsModal}
      className="modal-dialog"
      aria-labelledby="ticket-title"
      onCancel={handleCancel}
      onClick={handleBackdropClick}
    >
      <div className="modal-panel">
        <div className="modal-header">
          <div className="modal-title-group" id="ticket-title">
            <span className="modal-ticker">{stock.ticker}</span>
            <span className="modal-name">{stock.name}</span>
          </div>
          <button className="modal-close" onClick={onClose} aria-label="Close" disabled={isPending}>
            ✕
          </button>
        </div>

        <div className="modal-price-snapshot">
          <span className="modal-price-label">Price at open</span>
          <span className="modal-price-value">{formatCurrency(stock.price)}</span>
        </div>

        <form className="modal-form" action={submitAction}>
          <div className="modal-field">
            <span className="modal-label">Side</span>
            <ToggleGroup options={SIDE_OPTIONS} value={side} onChange={setSide} />
          </div>

          <div className="modal-field">
            <span className="modal-label">Order type</span>
            <ToggleGroup options={TYPE_OPTIONS} value={type} onChange={selectType} />
          </div>

          <div className="modal-field">
            <label className="modal-label" htmlFor="qty">
              Quantity
            </label>
            <input
              id="qty"
              name="quantity"
              className="modal-input"
              type="number"
              min="0.0001"
              step="any"
              placeholder="e.g. 10"
              value={quantity}
              onChange={(e) => {
                setQuantity(e.target.value);
              }}
              autoFocus
            />
          </div>

          <div className="modal-field">
            {type === 'MARKET' ? (
              <>
                <span className="modal-label">Market price</span>
                <div className="modal-input modal-input--readonly">{formatCurrency(stock.price)}</div>
              </>
            ) : (
              <>
                <label className="modal-label" htmlFor="limit-price">
                  Limit price
                </label>
                <input
                  id="limit-price"
                  name="limitPrice"
                  className="modal-input"
                  type="number"
                  min="0.0001"
                  step="any"
                  value={limitPrice}
                  onChange={(e) => {
                    setLimitPrice(e.target.value);
                  }}
                />
              </>
            )}
          </div>

          {result.phase !== 'editing' && (
            <p
              className={`modal-status modal-status--${result.phase === 'submitted' ? 'ok' : 'err'}`}
              role={result.phase === 'failed' ? 'alert' : 'status'}
            >
              {result.message}
            </p>
          )}

          <div className="modal-actions">
            <button type="button" className="btn-secondary" onClick={onClose} disabled={isPending}>
              {result.phase === 'submitted' ? 'Close' : 'Cancel'}
            </button>
            <SubmitButton disabled={locked} />
          </div>
        </form>
      </div>
    </dialog>
  );
}

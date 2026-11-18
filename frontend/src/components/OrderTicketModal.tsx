import { useState, type FormEvent, type MouseEvent } from 'react';
import { placeOrder } from '../api/ordersApi';
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

/** The ticket's lifecycle as one union instead of separate `submitting` and `status` flags. */
type TicketState =
  | { readonly phase: 'editing' }
  | { readonly phase: 'submitting' }
  | { readonly phase: 'submitted'; readonly message: string }
  | { readonly phase: 'failed'; readonly message: string };

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

export function OrderTicketModal({ stock, accountId, onClose }: OrderTicketModalProps) {
  const [side, setSide] = useState<OrderSide>('BUY');
  const [type, setType] = useState<OrderType>('MARKET');
  const [quantity, setQuantity] = useState('');
  const [limitPrice, setLimitPrice] = useState(stock.price.toFixed(2));
  const [ticket, setTicket] = useState<TicketState>({ phase: 'editing' });

  const locked = ticket.phase === 'submitting' || ticket.phase === 'submitted';

  function selectType(next: OrderType): void {
    setType(next);
    setLimitPrice(stock.price.toFixed(2));
  }

  async function submit(): Promise<void> {
    const built = buildRequest({ accountId, ticker: stock.ticker, side }, type, quantity, limitPrice);
    if (!built.ok) {
      setTicket({ phase: 'failed', message: built.error });
      return;
    }
    setTicket({ phase: 'submitting' });
    try {
      const response = await placeOrder(built.request);
      setTicket({ phase: 'submitted', message: response.message ?? 'Order submitted.' });
    } catch (err: unknown) {
      setTicket({ phase: 'failed', message: toErrorMessage(err) });
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault();
    void submit();
  }

  function handleBackdropClick(event: MouseEvent<HTMLDivElement>): void {
    if (event.target === event.currentTarget) onClose();
  }

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick}>
      <div className="modal-panel" role="dialog" aria-modal="true" aria-labelledby="ticket-title">
        <div className="modal-header">
          <div className="modal-title-group" id="ticket-title">
            <span className="modal-ticker">{stock.ticker}</span>
            <span className="modal-name">{stock.name}</span>
          </div>
          <button className="modal-close" onClick={onClose} aria-label="Close">
            ✕
          </button>
        </div>

        <div className="modal-price-snapshot">
          <span className="modal-price-label">Price at open</span>
          <span className="modal-price-value">{formatCurrency(stock.price)}</span>
        </div>

        <form className="modal-form" onSubmit={handleSubmit}>
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

          {(ticket.phase === 'submitted' || ticket.phase === 'failed') && (
            <p className={`modal-status modal-status--${ticket.phase === 'submitted' ? 'ok' : 'err'}`}>
              {ticket.message}
            </p>
          )}

          <div className="modal-actions">
            <button type="button" className="btn-secondary" onClick={onClose} disabled={locked}>
              Cancel
            </button>
            <button type="submit" className="btn-primary" disabled={locked}>
              {ticket.phase === 'submitting' ? 'Placing…' : 'Place Order'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

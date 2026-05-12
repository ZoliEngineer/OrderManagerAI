import React, { useState, useEffect } from 'react';
import { placeOrder } from '../api/ordersApi';
import './OrderTicketModal.css';

function OrderTicketModal({ stock, accountId, onClose }) {
  const [side, setSide] = useState('BUY');
  const [type, setType] = useState('MARKET');
  const [quantity, setQuantity] = useState('');
  const [limitPrice, setLimitPrice] = useState(stock.price.toFixed(2));
  const [status, setStatus] = useState(null); // { ok: bool, message: string }
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setLimitPrice(stock.price.toFixed(2));
  }, [type, stock.price]);

  async function handleSubmit(e) {
    e.preventDefault();
    if (!quantity || Number(quantity) <= 0) {
      setStatus({ ok: false, message: 'Enter a valid quantity.' });
      return;
    }

    const req = {
      accountId,
      ticker: stock.ticker,
      side,
      type,
      quantity: Number(quantity),
      ...(type === 'LIMIT' && { limitPrice: Number(limitPrice) }),
    };

    setSubmitting(true);
    setStatus(null);
    try {
      const res = await placeOrder(req);
      setStatus({ ok: true, message: res.message ?? 'Order submitted.' });
    } catch (err) {
      setStatus({ ok: false, message: err.message });
    } finally {
      setSubmitting(false);
    }
  }

  function handleBackdropClick(e) {
    if (e.target === e.currentTarget) onClose();
  }

  return (
    <div className="modal-backdrop" onClick={handleBackdropClick}>
      <div className="modal-panel" role="dialog" aria-modal="true">

        <div className="modal-header">
          <div className="modal-title-group">
            <span className="modal-ticker">{stock.ticker}</span>
            <span className="modal-name">{stock.name}</span>
          </div>
          <button className="modal-close" onClick={onClose} aria-label="Close">✕</button>
        </div>

        <div className="modal-price-snapshot">
          <span className="modal-price-label">Price at open</span>
          <span className="modal-price-value">${stock.price.toFixed(2)}</span>
        </div>

        <form className="modal-form" onSubmit={handleSubmit}>

          <div className="modal-field">
            <label className="modal-label">Side</label>
            <div className="toggle-group">
              <button type="button"
                className={`toggle-btn buy${side === 'BUY' ? ' active' : ''}`}
                onClick={() => setSide('BUY')}>BUY</button>
              <button type="button"
                className={`toggle-btn sell${side === 'SELL' ? ' active' : ''}`}
                onClick={() => setSide('SELL')}>SELL</button>
            </div>
          </div>

          <div className="modal-field">
            <label className="modal-label">Order type</label>
            <div className="toggle-group">
              <button type="button"
                className={`toggle-btn${type === 'MARKET' ? ' active' : ''}`}
                onClick={() => setType('MARKET')}>Market</button>
              <button type="button"
                className={`toggle-btn${type === 'LIMIT' ? ' active' : ''}`}
                onClick={() => setType('LIMIT')}>Limit</button>
            </div>
          </div>

          <div className="modal-field">
            <label className="modal-label" htmlFor="qty">Quantity</label>
            <input
              id="qty"
              className="modal-input"
              type="number"
              min="0.0001"
              step="any"
              placeholder="e.g. 10"
              value={quantity}
              onChange={e => setQuantity(e.target.value)}
              autoFocus
            />
          </div>

          <div className="modal-field">
            <label className="modal-label">
              {type === 'MARKET' ? 'Market price' : 'Limit price'}
            </label>
            {type === 'MARKET' ? (
              <div className="modal-input modal-input--readonly">
                ${stock.price.toFixed(2)}
              </div>
            ) : (
              <input
                className="modal-input"
                type="number"
                min="0.0001"
                step="any"
                value={limitPrice}
                onChange={e => setLimitPrice(e.target.value)}
              />
            )}
          </div>

          {status && (
            <p className={`modal-status ${status.ok ? 'modal-status--ok' : 'modal-status--err'}`}>
              {status.message}
            </p>
          )}

          <div className="modal-actions">
            <button type="button" className="btn-secondary" onClick={onClose} disabled={submitting || status?.ok}>Cancel</button>
            <button type="submit" className="btn-primary" disabled={submitting || status?.ok}>
              {submitting ? 'Placing…' : 'Place Order'}
            </button>
          </div>

        </form>
      </div>
    </div>
  );
}

export default OrderTicketModal;

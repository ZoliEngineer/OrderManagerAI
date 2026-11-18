import { useEffect, useRef, useState } from 'react';
import { useMarketData } from '../hooks/useMarketData';
import type { AccountId } from '../types/account';
import type { Stock, StockQuote } from '../types/marketData';
import { formatCurrency, formatSigned } from '../utils/format';
import { OrderTicketModal } from './OrderTicketModal';
import './MarketDataTable.css';

interface MarketDataTableProps {
  readonly selectedAccountId: AccountId | null;
}

type Direction = 'up' | 'down' | 'flat';
/** Template literal type: exactly the CSS classes that exist ('price-up' | 'price-down' | 'price-flat'). */
type DirectionClass = `price-${Direction}`;
type FlashClass = `flash-${Exclude<Direction, 'flat'>}`;

const FLASH_MS = 600;
const WARNING_MS = 3000;
const ARROW: Readonly<Record<Direction, string>> = { up: '▲', down: '▼', flat: '' };

const directionOf = (value: number): Direction => (value > 0 ? 'up' : value < 0 ? 'down' : 'flat');
const directionClass = (value: number): DirectionClass => `price-${directionOf(value)}`;

/** Briefly flags each ticker whose price moved since the previous render. */
function usePriceFlashes(stocks: readonly Stock[]): Readonly<Partial<Record<string, FlashClass>>> {
  const previous = useRef(new Map<string, number>());
  const [flashes, setFlashes] = useState<Readonly<Partial<Record<string, FlashClass>>>>({});

  useEffect(() => {
    const changed: Partial<Record<string, FlashClass>> = {};
    for (const { ticker, price } of stocks) {
      const before = previous.current.get(ticker);
      if (before !== undefined && before !== price) changed[ticker] = price > before ? 'flash-up' : 'flash-down';
      previous.current.set(ticker, price);
    }
    const tickers = Object.keys(changed);
    if (tickers.length === 0) return;

    // eslint-disable-next-line react-hooks/set-state-in-effect -- the flash is a reaction to new props, by design
    setFlashes((prev) => ({ ...prev, ...changed }));
    const timer = setTimeout(() => {
      setFlashes((prev) => Object.fromEntries(Object.entries(prev).filter(([ticker]) => !tickers.includes(ticker))));
    }, FLASH_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [stocks]);

  return flashes;
}

interface StockRowProps {
  readonly stock: Stock;
  readonly flash: FlashClass | undefined;
  readonly onSelect: (quote: StockQuote) => void;
}

function StockRow({ stock, flash, onSelect }: StockRowProps) {
  const { ticker, name, price, lastChange, changePercent, totalChange } = stock;
  const dayClass = directionClass(totalChange ?? 0);

  return (
    <tr
      className={flash ?? ''}
      style={{ cursor: 'pointer' }}
      onClick={() => {
        onSelect({ ticker, name, price });
      }}
    >
      <td>
        <span className="ticker-badge">{ticker}</span>
      </td>
      <td>{name}</td>
      <td className="align-right">
        <span className="price-value">{formatCurrency(price)}</span>
        {lastChange !== null && (
          <span className={`price-tick ${directionClass(lastChange)}`}>
            {ARROW[directionOf(lastChange)]}
            {formatSigned(lastChange)}
          </span>
        )}
      </td>
      <td className="align-right">
        {changePercent !== null && (
          <>
            <span className={`day-change ${dayClass}`}>{formatSigned(changePercent)}%</span>
            {totalChange !== null && <span className={`day-change-pct ${dayClass}`}>{formatSigned(totalChange)}</span>}
          </>
        )}
      </td>
    </tr>
  );
}

export function MarketDataTable({ selectedAccountId }: MarketDataTableProps) {
  const marketData = useMarketData();
  const stocks = marketData.status === 'success' ? marketData.data : [];
  const flashes = usePriceFlashes(stocks);
  const [selectedStock, setSelectedStock] = useState<StockQuote | null>(null);
  const [noAccountWarning, setNoAccountWarning] = useState(false);

  useEffect(() => {
    if (!noAccountWarning) return;
    const timer = setTimeout(() => {
      setNoAccountWarning(false);
    }, WARNING_MS);
    return () => {
      clearTimeout(timer);
    };
  }, [noAccountWarning]);

  if (marketData.status === 'idle' || marketData.status === 'loading') {
    return <p className="status-loading">Loading market data...</p>;
  }
  if (marketData.status === 'error') return <p className="status-error">Error: {marketData.error}</p>;

  function handleSelect(quote: StockQuote): void {
    if (selectedAccountId === null) setNoAccountWarning(true);
    else setSelectedStock(quote);
  }

  return (
    <>
      {noAccountWarning && <div className="no-account-warning">Select an account before trading.</div>}
      {selectedStock && selectedAccountId && (
        <OrderTicketModal
          stock={selectedStock}
          accountId={selectedAccountId}
          onClose={() => {
            setSelectedStock(null);
          }}
        />
      )}
      <table className="market-table">
        <thead>
          <tr>
            <th>Ticker</th>
            <th>Name</th>
            <th className="align-right">Price (USD)</th>
            <th className="align-right">Day Change</th>
          </tr>
        </thead>
        <tbody>
          {stocks.map((stock) => (
            <StockRow key={stock.ticker} stock={stock} flash={flashes[stock.ticker]} onSelect={handleSelect} />
          ))}
        </tbody>
      </table>
    </>
  );
}

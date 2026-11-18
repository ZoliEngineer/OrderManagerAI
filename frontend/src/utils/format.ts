const usd = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const quantity = new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 });
const dateTime = new Intl.DateTimeFormat('en-US', {
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hour12: false,
});

const PLACEHOLDER = '—';

export const formatCurrency = (value: number): string => usd.format(value);

/** Null and zero both mean "no price yet" in the order blotter. */
export const formatPrice = (value: number | null): string =>
  value === null || value === 0 ? PLACEHOLDER : usd.format(value);

export const formatQuantity = (value: number | null): string =>
  value === null ? PLACEHOLDER : quantity.format(value);

export const formatTimestamp = (iso: string | null): string =>
  iso ? dateTime.format(new Date(iso)) : PLACEHOLDER;

/** "+1.23" / "-1.23" / "0.00" */
export const formatSigned = (value: number): string => `${value > 0 ? '+' : ''}${value.toFixed(2)}`;

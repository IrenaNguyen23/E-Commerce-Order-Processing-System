/**
 * Presentation helpers.
 *
 * `Intl` formatters are expensive to construct, so they are memoised per locale/currency —
 * a product grid renders 24 prices per page and a naive `new Intl.NumberFormat()` per cell shows
 * up in a profile.
 */

const numberFormatters = new Map<string, Intl.NumberFormat>();

function currencyFormatter(currency: string, locale: string): Intl.NumberFormat {
  const key = `${locale}:${currency}`;
  let formatter = numberFormatters.get(key);
  if (!formatter) {
    formatter = new Intl.NumberFormat(locale, {
      style: 'currency',
      currency,
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
    numberFormatters.set(key, formatter);
  }
  return formatter;
}

/**
 * Money, as the backend sends it.
 *
 * Amounts arrive as JSON numbers with 4 decimal places of precision (`NUMERIC(19,4)`). That is
 * safely inside double precision for any realistic order total, so no big-decimal library is
 * warranted — but it is why formatting is centralised rather than done inline with `toFixed`.
 */
export function formatMoney(
  amount: number | string | null | undefined,
  currency = 'EUR',
  locale = 'en-IE',
): string {
  const value = typeof amount === 'string' ? Number(amount) : amount;
  if (value == null || !Number.isFinite(value)) return '—';
  return currencyFormatter(currency, locale).format(value);
}

export function formatNumber(value: number | null | undefined, locale = 'en-IE'): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return new Intl.NumberFormat(locale).format(value);
}

const dateTimeFormatter = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const dateFormatter = new Intl.DateTimeFormat('en-GB', {
  day: '2-digit',
  month: 'short',
  year: 'numeric',
});

/** Timestamps arrive as ISO-8601 UTC (`2026-08-26T10:00:00Z`). */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dateTimeFormatter.format(date);
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : dateFormatter.format(date);
}

/** "2 minutes ago" — used where the exact instant matters less than the recency. */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return '—';
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return '—';

  const seconds = Math.round((Date.now() - date.getTime()) / 1000);
  const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

  const divisions: Array<[number, Intl.RelativeTimeFormatUnit]> = [
    [60, 'second'],
    [60, 'minute'],
    [24, 'hour'],
    [7, 'day'],
    [4.34524, 'week'],
    [12, 'month'],
    [Number.POSITIVE_INFINITY, 'year'],
  ];

  let value = seconds;
  for (const [amount, unit] of divisions) {
    if (Math.abs(value) < amount) return relative.format(-Math.round(value), unit);
    value /= amount;
  }
  return relative.format(-Math.round(value), 'year');
}

/**
 * Turns a machine constant into readable prose: `INSUFFICIENT_FUNDS` -> `Insufficient funds`.
 * The backend does the same for notification bodies; this keeps the UI consistent with them.
 */
export function humanize(value: string | null | undefined): string {
  if (!value) return '—';
  const lower = value.replace(/_/g, ' ').toLowerCase();
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** First letters of a name, for avatar fallbacks. */
export function initials(name: string | null | undefined): string {
  if (!name) return '?';
  return name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');
}

export function truncate(value: string, max: number): string {
  return value.length <= max ? value : `${value.slice(0, max - 1)}…`;
}

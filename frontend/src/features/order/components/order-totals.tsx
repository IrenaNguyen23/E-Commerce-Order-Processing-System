import { formatMoney } from '@/utils/format';

/**
 * The money on an order, itemised.
 *
 * <p>Every component is shown, not just the total. A customer who can see goods, discount, tax and
 * delivery can add them up and get the number they are being charged; one shown a single figure
 * has to take it on trust, and the first time it looks wrong they ring somebody.
 *
 * <p>Rows for zero amounts are hidden rather than shown as "0.00" — with one exception. Free
 * delivery is worth saying out loud, because it is something the customer gained rather than
 * something that did not apply.
 */
export interface OrderTotalsProps {
  subtotal: number;
  discount: number;
  tax: number;
  shipping: number;
  total: number;
  currency: string;
  /** Shown next to the discount line, so the reduction has a name rather than being anonymous. */
  couponCode?: string | null;
  /** What the tax is called where this went. Falls back to the neutral "Tax". */
  taxName?: string | null;
  /** Included so a free delivery line can say so rather than being hidden as a zero. */
  hasDelivery?: boolean;
  deliveryNote?: string | null;
}

export function OrderTotals({
  subtotal,
  discount,
  tax,
  shipping,
  total,
  currency,
  couponCode,
  taxName,
  hasDelivery = true,
  deliveryNote,
}: OrderTotalsProps) {
  return (
    <dl className="space-y-2 text-sm">
      <Row label="Items" value={formatMoney(subtotal, currency)} />

      {discount > 0 ? (
        <Row
          label={couponCode ? `Discount (${couponCode})` : 'Discount'}
          value={`−${formatMoney(discount, currency)}`}
          tone="positive"
        />
      ) : null}

      {tax > 0 ? <Row label={taxName || 'Tax'} value={formatMoney(tax, currency)} /> : null}

      {hasDelivery ? (
        <Row
          label="Delivery"
          value={shipping > 0 ? formatMoney(shipping, currency) : 'Free'}
          hint={deliveryNote ?? undefined}
          tone={shipping > 0 ? undefined : 'positive'}
        />
      ) : null}

      <div className="flex items-baseline justify-between border-t pt-3">
        <dt className="font-medium">Total</dt>
        <dd className="text-xl font-semibold tabular">{formatMoney(total, currency)}</dd>
      </div>
    </dl>
  );
}

function Row({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: string;
  hint?: string;
  tone?: 'positive';
}) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="min-w-0 text-muted-foreground">
        {label}
        {hint ? <span className="block text-xs text-muted-foreground/80">{hint}</span> : null}
      </dt>
      <dd
        className={`shrink-0 tabular ${tone === 'positive' ? 'text-emerald-600 dark:text-emerald-400' : ''}`}
      >
        {value}
      </dd>
    </div>
  );
}

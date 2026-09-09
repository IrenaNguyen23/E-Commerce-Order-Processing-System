import { zodResolver } from '@hookform/resolvers/zod';
import { AlertTriangle, Check, Lock, MapPin, Tag, Truck } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Checkbox } from '@/components/ui/checkbox';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';
import { useCartProducts } from '@/features/cart/hooks';
import { selectIsAuthenticated, useAuthStore } from '@/features/auth/store';
import { useAddresses, useCreateAddress } from '@/features/user/addresses-api';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { formatMoney } from '@/utils/format';

import { GuestCheckoutGate } from '../components/guest-checkout-gate';
import { OrderTotals } from '../components/order-totals';
import { usePreviewCoupon, useShippingQuotes } from '../checkout-api';
import { useIdempotencyKey, usePlaceOrder } from '../hooks';
import { checkoutSchema, type CheckoutValues } from '../schemas';
import { SHIPPING_METHOD_LABELS, type CouponPreview, type ShippingMethod } from '../types';

/**
 * Checkout.
 *
 * The page's one job is that **the total shown is the total charged**. Everything below serves
 * that: delivery is quoted from the same rate card the order will be priced against, the coupon is
 * validated by the same service that will claim it, and tax is estimated from the same country
 * code that gets submitted.
 *
 * It is an estimate until the order is placed, and that is stated rather than hidden. The server
 * prices the order again when it arrives, which is what makes the number authoritative — and what
 * means a coupon can be refused here after passing a moment ago, if the last one went.
 */
export default function CheckoutPage() {
  const cart = useCartProducts();
  const placeOrder = usePlaceOrder();

  // One key for the lifetime of this screen, so a double-click or a retry after a network blip
  // returns the original order instead of placing a second one.
  const idempotencyKey = useIdempotencyKey();

  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const addresses = useAddresses();
  const createAddress = useCreateAddress();
  const previewCoupon = usePreviewCoupon();

  const [couponInput, setCouponInput] = useState('');
  const [coupon, setCoupon] = useState<CouponPreview | null>(null);
  const [couponError, setCouponError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    setValue,
    watch,
    reset,
    formState: { errors },
  } = useForm<CheckoutValues>({
    resolver: zodResolver(checkoutSchema),
    defaultValues: {
      address: {
        label: '',
        recipientName: '',
        line1: '',
        line2: '',
        city: '',
        region: '',
        postalCode: '',
        countryCode: '',
        phone: '',
      },
      shippingMethod: 'STANDARD',
      saveAddress: true,
    },
  });

  const defaultAddress = addresses.data?.find((address) => address.isDefault) ?? null;

  // Prefill from the saved default. `reset` rather than per-field `setValue` so the form's dirty
  // state stays accurate.
  useEffect(() => {
    if (!defaultAddress) return;
    reset({
      address: {
        label: defaultAddress.label ?? '',
        recipientName: defaultAddress.recipientName,
        line1: defaultAddress.line1,
        line2: defaultAddress.line2 ?? '',
        city: defaultAddress.city,
        region: defaultAddress.region ?? '',
        postalCode: defaultAddress.postalCode ?? '',
        countryCode: defaultAddress.countryCode,
        phone: defaultAddress.phone ?? '',
      },
      shippingMethod: 'STANDARD',
      saveAddress: false,
    });
  }, [defaultAddress, reset]);

  const countryCode = (watch('address.countryCode') ?? '').toUpperCase();
  const shippingMethod = watch('shippingMethod');
  const saveAddress = watch('saveAddress');

  // What the customer pays for the goods, which is what a free-delivery threshold is measured
  // against. Comparing against the list price would hand out free delivery a basket did not earn.
  const goodsAfterDiscount = Math.max(0, cart.total - (coupon?.goodsDiscount ?? 0));

  const quotes = useShippingQuotes({
    countryCode: countryCode.length === 2 ? countryCode : undefined,
    itemCount: cart.lines.reduce((sum, line) => sum + line.quantity, 0),
    goodsAfterDiscount,
    currency: cart.currency,
  });

  const chosenQuote = quotes.data?.find((quote) => quote.method === shippingMethod) ?? null;

  // The delivery figure the summary shows. A free-delivery coupon takes it to zero here so the
  // page agrees with what the server will charge.
  const shippingAmount = coupon?.type === 'FREE_SHIPPING' ? 0 : (chosenQuote?.amount ?? 0);

  /**
   * Tax cannot be computed here — the rates live on the server and depend on each product's
   * category. Rather than guess a rate and show a number that will not match the invoice, the
   * summary says the total is an estimate until the order is placed.
   */
  const estimatedTotal = goodsAfterDiscount + shippingAmount;

  // If the chosen method stops being available for a new destination, fall back rather than
  // submitting a method the server will refuse.
  useEffect(() => {
    const first = quotes.data?.[0];
    if (!first) return;
    if (quotes.data?.some((quote) => quote.method === shippingMethod)) return;
    setValue('shippingMethod', first.method);
  }, [quotes.data, shippingMethod, setValue]);

  const applyCoupon = async () => {
    const code = couponInput.trim();
    if (!code) return;
    setCouponError(null);
    try {
      const preview = await previewCoupon.mutateAsync({
        code,
        basketAmount: cart.total,
        shippingAmount: chosenQuote?.amount ?? 0,
        currency: cart.currency,
      });
      setCoupon(preview);
    } catch (error) {
      // Shown next to the field rather than as a toast: the message says *which* reason —
      // expired, not started, basket too small — and that belongs where they are looking.
      setCouponError(normalizeError(error).message);
      setCoupon(null);
    }
  };

  const clearCoupon = () => {
    setCoupon(null);
    setCouponInput('');
    setCouponError(null);
  };

  const availableMethods = useMemo(() => quotes.data ?? [], [quotes.data]);

  // Nothing to check out. `replace` so the back button does not bounce between the two.
  if (cart.isEmpty && !placeOrder.isPending && !placeOrder.isSuccess) {
    return <Navigate to={paths.cart} replace />;
  }

  // An identity first, chosen or not. Everything below assumes a session — the basket is
  // stored against it, the order is placed for it — and a guest gets a real one.
  if (!isAuthenticated) {
    return (
      <div>
        <PageHeader
          title="Checkout"
          breadcrumbs={[
            { label: 'Home', to: paths.home },
            { label: 'Basket', to: paths.cart },
            { label: 'Checkout' },
          ]}
        />
        <GuestCheckoutGate />
      </div>
    );
  }

  const onSubmit = handleSubmit((values) => {
    const address = {
      recipientName: values.address.recipientName,
      phone: values.address.phone || undefined,
      line1: values.address.line1,
      line2: values.address.line2 || undefined,
      city: values.address.city,
      region: values.address.region || undefined,
      postalCode: values.address.postalCode || undefined,
      countryCode: values.address.countryCode,
    };

    if (values.saveAddress) {
      // Fire and forget. Failing to save an address to the book must not stop an order the
      // customer has already decided to place — the order carries its own copy regardless.
      createAddress.mutate({
        ...address,
        label: values.address.label || undefined,
        makeDefault: (addresses.data?.length ?? 0) === 0,
      });
    }

    placeOrder.mutate({
      items: cart.lines.map((line) => ({
        productId: line.productId,
        quantity: line.quantity,
      })),
      shippingAddress: address,
      shippingMethod: values.shippingMethod,
      couponCode: coupon?.code,
      idempotencyKey,
    });
  });

  return (
    <div>
      <PageHeader
        title="Checkout"
        breadcrumbs={[
          { label: 'Home', to: paths.home },
          { label: 'Basket', to: paths.cart },
          { label: 'Checkout' },
        ]}
      />

      <form onSubmit={onSubmit} className="grid gap-8 lg:grid-cols-[1fr_360px]" noValidate>
        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <MapPin className="h-4 w-4 text-muted-foreground" aria-hidden />
                Delivery address
              </CardTitle>
            </CardHeader>

            <CardContent className="space-y-4">
              {(addresses.data?.length ?? 0) > 0 ? (
                <div className="rounded-md border bg-muted/40 p-3">
                  <p className="text-xs text-muted-foreground">
                    Prefilled from your saved address. Edit any field to send this order somewhere
                    else — your saved addresses are unchanged unless you tick the box below. Manage
                    them in{' '}
                    <Link to={paths.addresses} className="underline underline-offset-4">
                      your account
                    </Link>
                    .
                  </p>
                </div>
              ) : null}

              <div className="grid gap-4 sm:grid-cols-2">
                <FormField
                  id="recipientName"
                  label="Recipient"
                  className="sm:col-span-2"
                  autoComplete="name"
                  error={errors.address?.recipientName?.message}
                  {...register('address.recipientName')}
                />
                <FormField
                  id="line1"
                  label="Street and number"
                  className="sm:col-span-2"
                  autoComplete="address-line1"
                  error={errors.address?.line1?.message}
                  {...register('address.line1')}
                />
                <FormField
                  id="line2"
                  label="Apartment, suite"
                  optional
                  className="sm:col-span-2"
                  autoComplete="address-line2"
                  error={errors.address?.line2?.message}
                  {...register('address.line2')}
                />
                <FormField
                  id="postalCode"
                  label="Postal code"
                  optional
                  autoComplete="postal-code"
                  error={errors.address?.postalCode?.message}
                  {...register('address.postalCode')}
                />
                <FormField
                  id="city"
                  label="City"
                  autoComplete="address-level2"
                  error={errors.address?.city?.message}
                  {...register('address.city')}
                />
                <FormField
                  id="region"
                  label="Region or province"
                  optional
                  autoComplete="address-level1"
                  error={errors.address?.region?.message}
                  {...register('address.region')}
                />
                <FormField
                  id="countryCode"
                  label="Country code"
                  hint="Two letters, e.g. NL. This decides your tax and delivery cost."
                  autoComplete="country"
                  maxLength={2}
                  className="uppercase"
                  error={errors.address?.countryCode?.message}
                  {...register('address.countryCode')}
                />
                <FormField
                  id="phone"
                  label="Phone"
                  optional
                  type="tel"
                  autoComplete="tel"
                  error={errors.address?.phone?.message}
                  {...register('address.phone')}
                />
              </div>

              <label className="flex items-center gap-2 text-sm">
                <Checkbox
                  checked={saveAddress}
                  onCheckedChange={(checked) => setValue('saveAddress', checked === true)}
                />
                Save this address to my address book
              </label>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Truck className="h-4 w-4 text-muted-foreground" aria-hidden />
                Delivery
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {countryCode.length !== 2 ? (
                <p className="text-sm text-muted-foreground">
                  Enter a country code above and we will price your delivery options.
                </p>
              ) : quotes.isLoading ? (
                <p className="text-sm text-muted-foreground">Pricing delivery…</p>
              ) : availableMethods.length === 0 ? (
                <p className="text-sm text-destructive">
                  We cannot deliver to {countryCode} at the moment.
                </p>
              ) : (
                <div className="space-y-2">
                  {availableMethods.map((quote) => {
                    const selected = quote.method === shippingMethod;
                    const waived = coupon?.type === 'FREE_SHIPPING' && quote.amount > 0;
                    return (
                      <button
                        key={quote.method}
                        type="button"
                        onClick={() => setValue('shippingMethod', quote.method as ShippingMethod)}
                        aria-pressed={selected}
                        className={`flex w-full items-center justify-between gap-3 rounded-md border p-3 text-left text-sm transition ${
                          selected ? 'border-primary bg-primary/5' : 'hover:bg-muted/50'
                        }`}
                      >
                        <span className="min-w-0">
                          <span className="font-medium">
                            {SHIPPING_METHOD_LABELS[quote.method]}
                          </span>
                          <span className="block text-xs text-muted-foreground">
                            {quote.description}
                          </span>
                        </span>
                        <span className="shrink-0 tabular">
                          {quote.amount > 0 && !waived ? (
                            formatMoney(quote.amount, quote.currency)
                          ) : (
                            <span className="text-emerald-600 dark:text-emerald-400">Free</span>
                          )}
                        </span>
                      </button>
                    );
                  })}
                </div>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <Tag className="h-4 w-4 text-muted-foreground" aria-hidden />
                Discount code
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              {coupon ? (
                <div className="flex items-center justify-between gap-3 rounded-md border border-emerald-500/30 bg-emerald-500/5 p-3">
                  <span className="min-w-0 text-sm">
                    <span className="flex items-center gap-2 font-medium">
                      <Check className="h-4 w-4 text-emerald-600" aria-hidden />
                      {coupon.code}
                    </span>
                    <span className="block text-xs text-muted-foreground">
                      {coupon.description ?? 'Applied to this order'}
                    </span>
                  </span>
                  <Button type="button" variant="ghost" size="sm" onClick={clearCoupon}>
                    Remove
                  </Button>
                </div>
              ) : (
                <>
                  <div className="flex gap-2">
                    <Input
                      aria-label="Discount code"
                      placeholder="WELCOME10"
                      value={couponInput}
                      maxLength={40}
                      onChange={(event) => setCouponInput(event.target.value)}
                      onKeyDown={(event) => {
                        if (event.key === 'Enter') {
                          // Otherwise Enter submits the order while the customer is still
                          // trying to apply a code.
                          event.preventDefault();
                          void applyCoupon();
                        }
                      }}
                    />
                    <Button
                      type="button"
                      variant="outline"
                      loading={previewCoupon.isPending}
                      disabled={!couponInput.trim()}
                      onClick={() => void applyCoupon()}
                    >
                      Apply
                    </Button>
                  </div>
                  {couponError ? (
                    <p className="text-sm text-destructive" role="alert">
                      {couponError}
                    </p>
                  ) : null}
                </>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Payment</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="text-sm text-muted-foreground">
                We take payment on the next screen, once your items are reserved. Nothing is
                charged until then.
              </p>
            </CardContent>
          </Card>
        </div>

        <div className="lg:sticky lg:top-24 lg:self-start">
          <Card>
            <CardContent className="space-y-4 p-6">
              <h2 className="font-semibold">Your order</h2>

              <ul className="space-y-2">
                {cart.lines.map((line) => (
                  <li key={line.productId} className="flex justify-between gap-3 text-sm">
                    <span className="min-w-0">
                      <span className="tabular">{line.quantity}×</span>{' '}
                      <span className="text-muted-foreground">
                        {line.product?.name ?? line.productId}
                      </span>
                    </span>
                    <span className="shrink-0 tabular">
                      {formatMoney(line.subtotal, line.currency)}
                    </span>
                  </li>
                ))}
              </ul>

              <Separator />

              <OrderTotals
                subtotal={cart.total}
                discount={coupon?.goodsDiscount ?? 0}
                tax={0}
                shipping={shippingAmount}
                total={estimatedTotal}
                currency={cart.currency}
                couponCode={coupon?.code}
                hasDelivery={Boolean(chosenQuote)}
                deliveryNote={chosenQuote?.description}
              />

              {/*
                Said plainly rather than hidden. Tax rates depend on each product's category and
                live on the server; showing a guessed figure here would mean the invoice does not
                match the page, which is worse than saying the total will firm up.
              */}
              <p className="text-xs text-muted-foreground">
                Tax is added when your order is placed, based on where it is going. You will see
                the final total on the next screen, before you pay.
              </p>

              {cart.blockingIssues.length > 0 ? (
                <div
                  role="alert"
                  className="space-y-1 rounded-md border border-destructive/30 bg-destructive/5 p-3"
                >
                  {cart.blockingIssues.map((issue) => (
                    <p key={issue} className="flex gap-2 text-xs text-destructive">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden />
                      {issue}
                    </p>
                  ))}
                </div>
              ) : null}

              <Button
                type="submit"
                className="w-full"
                size="lg"
                loading={placeOrder.isPending}
                disabled={!cart.canCheckout || placeOrder.isPending}
              >
                <Lock aria-hidden />
                Place order
              </Button>

              <p className="text-center text-xs text-muted-foreground">
                Your order is accepted immediately, then stock and payment resolve in the
                background. You will see it happen on the next screen.
              </p>

              {/*
                Shown at the point of payment rather than only in the footer. Consumer law calls
                this pre-contract information: it has to be in front of the buyer before they
                commit, not findable somewhere on the site afterwards.
              */}
              <p className="text-center text-xs text-muted-foreground">
                Placing this order means you accept our{' '}
                <Link to={paths.terms} className="underline hover:text-foreground">
                  terms of sale
                </Link>
                . You can change your mind — see{' '}
                <Link to={paths.returns} className="underline hover:text-foreground">
                  returns and refunds
                </Link>
                .
              </p>
            </CardContent>
          </Card>
        </div>
      </form>
    </div>
  );
}

const FormField = ({
  id,
  label,
  error,
  optional,
  hint,
  className,
  ...props
}: React.InputHTMLAttributes<HTMLInputElement> & {
  id: string;
  label: string;
  error?: string;
  optional?: boolean;
  hint?: string;
}) => (
  <div className={className?.includes('sm:col-span') ? className : undefined}>
    <Label htmlFor={id}>
      {label}
      {optional ? <span className="ml-1 text-muted-foreground">(optional)</span> : null}
    </Label>
    <Input
      id={id}
      className={`mt-2 ${className?.includes('uppercase') ? 'uppercase' : ''}`}
      aria-invalid={Boolean(error)}
      {...props}
    />
    {hint && !error ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
    {error ? <p className="mt-1 text-sm text-destructive">{error}</p> : null}
  </div>
);

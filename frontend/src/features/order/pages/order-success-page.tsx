import { CheckCircle2, Clock, Copy, PackageX, Receipt } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';

import { ErrorState, LoadingState } from '@/components/common/states';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { useCopyToClipboard } from '@/hooks/use-copy';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';

import { usePaymentByOrder } from '@/features/payment/hooks';
import { PaymentForm } from '@/features/payment/components/payment-form';

import { OrderItemsTable } from '../components/order-items-table';
import { OrderTotals } from '../components/order-totals';
import { OrderTimeline } from '../components/order-timeline';
import { useOrderPolling } from '../hooks';
import { deliveryWindow, SHIPPING_METHOD_LABELS } from '../types';

/**
 * The screen the customer lands on straight after placing an order.
 *
 * This is where the asynchronous backend stops being a liability and becomes the feature. The
 * order comes back `CREATED`; stock, payment and confirmation land over the next few seconds and
 * the timeline fills in live. Polling stops the moment the saga is terminal, and gives up
 * gracefully if it stalls — the customer is never left staring at a spinner that means nothing.
 */
export default function OrderSuccessPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const { data: order, isLoading, isError, error, refetch, isSagaRunning, hasTimedOut } =
    useOrderPolling(orderId);
  const { copy } = useCopyToClipboard();

  // Only asked for while the saga is running. With a real acquirer the payment row arrives
  // carrying the secret this browser needs to finish the charge; with the simulated one it is
  // already settled by the time anybody looks, and the form below never appears.
  const { data: payment } = usePaymentByOrder(orderId, isSagaRunning, isSagaRunning);

  if (isError) {
    return (
      <ErrorState
        error={normalizeError(error)}
        onRetry={() => void refetch()}
        title="Could not load your order"
      />
    );
  }

  if (isLoading || !order) {
    return <LoadingState label="Loading your order…" />;
  }

  const cancelled = order.status === 'CANCELLED';
  const completed = order.status === 'COMPLETED';
  // "Processing" would be a lie while the customer is the one holding things up.
  const awaitingPayment = payment?.status === 'PENDING' && Boolean(payment.clientSecret);

  return (
    <div className="mx-auto max-w-3xl">
      <div className="mb-8 text-center">
        <span
          className={
            cancelled
              ? 'mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-destructive/10 text-destructive'
              : completed
                ? 'mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-success/10 text-success'
                : 'mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-primary/10 text-primary'
          }
          aria-hidden
        >
          {cancelled ? (
            <PackageX className="h-7 w-7" />
          ) : completed ? (
            <CheckCircle2 className="h-7 w-7" />
          ) : (
            <Clock className="h-7 w-7" />
          )}
        </span>

        <h1 className="mt-4 text-2xl font-semibold tracking-tight">
          {cancelled
            ? 'Your order could not be completed'
            : completed
              ? 'Your order is confirmed'
              : awaitingPayment
                ? 'One step left'
                : 'Your order is being processed'}
        </h1>

        <p className="mt-2 text-sm text-muted-foreground">
          {cancelled
            ? 'Nothing has been charged. Anything reserved has been released.'
            : completed
              ? 'We have emailed you a confirmation.'
              : awaitingPayment
                ? 'Your items are reserved. One step left — complete the payment below.'
                : 'This usually takes a few seconds. You can leave this page — it will keep going.'}
        </p>

        <div className="mt-4 inline-flex items-center gap-2 rounded-md border bg-muted/40 px-3 py-2">
          <Receipt className="h-4 w-4 text-muted-foreground" aria-hidden />
          <span className="font-mono text-sm font-medium">{order.orderNumber}</span>
          <button
            type="button"
            onClick={() => copy(order.orderNumber, 'Order number copied')}
            className="rounded-sm p-1 text-muted-foreground transition-colors hover:text-foreground"
            aria-label="Copy order number"
          >
            <Copy className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>

      {/* The customer's move, and the only part of this screen that is not just reporting. Shown
          above the timeline because an outstanding payment is the most important thing on the
          page — everything below it is waiting on this. */}
      {payment?.status === 'PENDING' && payment.clientSecret ? (
        <PaymentForm
          clientSecret={payment.clientSecret}
          amount={payment.amount}
          currency={payment.currency}
        />
      ) : null}

      <Card className="mb-6">
        <CardContent className="p-6">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="font-semibold">Progress</h2>
            {isSagaRunning ? (
              <span className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="h-2 w-2 animate-pulse rounded-full bg-primary" aria-hidden />
                Updating live
              </span>
            ) : null}
          </div>

          <OrderTimeline order={order} />

          {/* A stalled saga usually means a downstream consumer is down. Say so plainly and stop
              polling, rather than spinning forever and draining the customer's battery. */}
          {hasTimedOut ? (
            <div className="mt-4 rounded-md border border-warning/40 bg-warning/5 p-3">
              <p className="text-sm">
                This is taking longer than usual. Your order is safe and will finish processing —
                check back shortly, or refresh to see the latest.
              </p>
              <Button variant="outline" size="sm" className="mt-3" onClick={() => void refetch()}>
                Refresh
              </Button>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <div className="mb-6">
        <h2 className="mb-3 font-semibold">Items</h2>
        <OrderItemsTable order={order} />
      </div>

      <Card className="mb-8">
        <CardContent className="grid gap-6 p-6 sm:grid-cols-2">
          <div>
            <p className="text-xs uppercase tracking-wide text-muted-foreground">Delivering to</p>
            <p className="mt-1 text-sm">{order.shippingAddress}</p>
            {order.delivery?.method ? (
              <p className="mt-1 text-xs text-muted-foreground">
                {SHIPPING_METHOD_LABELS[order.delivery.method]}
                {deliveryWindow(order) ? ` — ${deliveryWindow(order)}` : ''}
              </p>
            ) : null}
          </div>
          <div>
            {/* Itemised rather than a single figure. This is the first screen showing the real
                total — the checkout page could only estimate it, because the tax rates live on
                the server — so it has to be the screen that shows how it was arrived at. */}
            <OrderTotals
              subtotal={order.subtotalAmount}
              discount={order.discountTotal}
              tax={order.taxTotal}
              shipping={order.shippingAmount}
              total={order.totalAmount}
              currency={order.currency}
              couponCode={order.couponCode}
              taxName={order.items.find((item) => item.taxName)?.taxName}
              deliveryNote={deliveryWindow(order)}
            />
          </div>
        </CardContent>
      </Card>

      <div className="flex flex-wrap justify-center gap-3">
        <Button asChild>
          <Link to={paths.order(order.id)}>View order details</Link>
        </Button>
        <Button variant="outline" asChild>
          <Link to={paths.orders}>All my orders</Link>
        </Button>
        <Button variant="ghost" asChild>
          <Link to={paths.products}>Continue shopping</Link>
        </Button>
      </div>
    </div>
  );
}

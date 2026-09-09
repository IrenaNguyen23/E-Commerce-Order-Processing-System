import { useState } from 'react';
import { Copy, CreditCard, MapPin } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { ErrorState, LoadingState } from '@/components/common/states';
import { OrderStatusBadge, PaymentStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { usePaymentByOrder } from '@/features/payment/hooks';
import { PAYMENT_METHOD_LABELS } from '@/features/payment/types';
import { useCopyToClipboard } from '@/hooks/use-copy';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { formatDateTime, formatMoney, humanize } from '@/utils/format';

import { OrderItemsTable } from '../components/order-items-table';
import { OrderTotals } from '../components/order-totals';
import { ShipmentTracker } from '../components/shipment-tracker';
import { ReturnSummary } from '../components/return-summary';
import { OrderTimeline } from '../components/order-timeline';
import { useCancelOrder } from '../hooks';
import { useOrderPolling } from '../hooks';
import { deliveryWindow, SHIPPING_METHOD_LABELS } from '../types';

export default function OrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const { data: order, isLoading, isError, error, refetch, isSagaRunning } =
    useOrderPolling(orderId);
  const { copy } = useCopyToClipboard();
  const cancelOrder = useCancelOrder();
  const [confirmingCancel, setConfirmingCancel] = useState(false);
  const [cancelReason, setCancelReason] = useState('');

  // The payment row only exists once the saga reaches the payment step, so only ask for it
  // after inventory has been reserved — otherwise every early view logs an expected 404.
  const payment = usePaymentByOrder(
    orderId,
    Boolean(order && order.status !== 'CREATED'),
  );

  if (isError) {
    return (
      <ErrorState
        error={normalizeError(error)}
        onRetry={() => void refetch()}
        title="Could not load this order"
      />
    );
  }

  if (isLoading || !order) {
    return <LoadingState label="Loading order…" />;
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title={order.orderNumber}
        description={`Placed ${formatDateTime(order.createdAt)}`}
        breadcrumbs={[
          { label: 'Home', to: paths.home },
          { label: 'My orders', to: paths.orders },
          { label: order.orderNumber },
        ]}
        actions={
          <>
            <OrderStatusBadge status={order.status} />
            <Button
              variant="outline"
              size="icon"
              onClick={() => copy(order.orderNumber, 'Order number copied')}
              aria-label="Copy order number"
            >
              <Copy aria-hidden />
            </Button>
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        <div className="space-y-6">
          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <CardTitle className="text-base">Progress</CardTitle>
              {isSagaRunning ? (
                <span className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span className="h-2 w-2 animate-pulse rounded-full bg-primary" aria-hidden />
                  Updating live
                </span>
              ) : null}
            </CardHeader>
            <CardContent>
              <OrderTimeline order={order} />
            </CardContent>
          </Card>

          <div>
            <h2 className="mb-3 font-semibold">Items</h2>
            <OrderItemsTable order={order} />
          </div>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <MapPin className="h-4 w-4 text-muted-foreground" aria-hidden />
                Delivery
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-2">
              {/* The formatted line the order froze at checkout, not one rebuilt from the
                  structured fields — a later change to the formatter must not alter what an
                  old order says was on its parcel. */}
              <p className="text-sm">{order.shippingAddress}</p>
              {order.delivery?.method ? (
                <p className="text-xs text-muted-foreground">
                  {SHIPPING_METHOD_LABELS[order.delivery.method]}
                  {deliveryWindow(order) ? ` — ${deliveryWindow(order)}` : ''}
                </p>
              ) : null}
            </CardContent>
          </Card>

          {/* Renders nothing until there is a parcel — an order that has not been picked yet
              has no shipment, and that is the normal state for the first day. */}
          <ShipmentTracker orderId={order.id} />

          {/* Nothing until the order has completed or there is already a return on it. */}
          <ReturnSummary orderId={order.id} orderStatus={order.status} />

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <CreditCard className="h-4 w-4 text-muted-foreground" aria-hidden />
                Payment
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              {payment.data ? (
                <>
                  <Row label="Status">
                    <PaymentStatusBadge status={payment.data.status} showIcon={false} />
                  </Row>
                  <Row label="Method">{PAYMENT_METHOD_LABELS[payment.data.method]}</Row>
                  <Row label="Amount">
                    <span className="tabular">
                      {formatMoney(payment.data.amount, payment.data.currency)}
                    </span>
                  </Row>
                  {payment.data.transactionId ? (
                    <Row label="Reference">
                      <span className="font-mono text-xs">{payment.data.transactionId}</span>
                    </Row>
                  ) : null}
                  {payment.data.failureReason ? (
                    <Row label="Reason">
                      <span className="text-destructive">
                        {humanize(payment.data.failureReason)}
                      </span>
                    </Row>
                  ) : null}
                </>
              ) : (
                <p className="text-muted-foreground">
                  {order.status === 'CREATED'
                    ? 'Payment starts once your stock is reserved.'
                    : 'No payment recorded for this order.'}
                </p>
              )}

              <Separator />

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
            </CardContent>
          </Card>

          {order.status === 'CANCELLED' ? (
            <Card className="border-destructive/30 bg-destructive/5">
              <CardContent className="p-5">
                <p className="text-sm font-medium">Order cancelled</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  Stopped at the {order.failedStep?.toLowerCase() ?? 'processing'} step
                  {order.failureReason ? `: ${humanize(order.failureReason)}` : ''}.
                </p>

                {/* This used to say "you have not been charged" unconditionally, which is true
                    only for an order cancelled before payment. An order cancelled after it was
                    paid for is owed a refund, and telling that customer they were never charged
                    is the worst possible thing to tell them. The payment knows; ask it. */}
                <p className="mt-2 text-sm text-muted-foreground">
                  {payment.data?.status === 'REFUNDED'
                    ? 'Your payment has been refunded. It can take a few days to appear on your statement.'
                    : payment.data?.status === 'COMPLETED'
                      ? 'Your refund is being processed. We will email you when it is on its way.'
                      : 'You have not been charged.'}
                </p>

                <Button variant="outline" size="sm" className="mt-4" asChild>
                  <Link to={paths.products}>Shop again</Link>
                </Button>
              </CardContent>
            </Card>
          ) : (
            <Card>
              <CardContent className="p-5">
                <p className="text-sm font-medium">Changed your mind?</p>
                <p className="mt-1 text-sm text-muted-foreground">
                  {payment.data?.status === 'COMPLETED'
                    ? 'Cancelling refunds what you paid and returns the items to stock.'
                    : 'Cancelling releases the items back to stock. Nothing has been charged.'}
                </p>
                <Button
                  variant="outline"
                  size="sm"
                  className="mt-4"
                  onClick={() => setConfirmingCancel(true)}
                >
                  Cancel this order
                </Button>
              </CardContent>
            </Card>
          )}
        </div>
      </div>

      <Dialog open={confirmingCancel} onOpenChange={setConfirmingCancel}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Cancel order {order.orderNumber}?</DialogTitle>
          </DialogHeader>

          <p className="text-sm text-muted-foreground">
            {payment.data?.status === 'COMPLETED'
              ? `We will refund ${formatMoney(order.totalAmount, order.currency)} and return the items to stock. Refunds usually reach your account within a few days.`
              : 'The items go back into stock. Nothing has been charged, so there is nothing to refund.'}
          </p>

          <div className="mt-4 space-y-2">
            <Label htmlFor="cancel-reason">Reason (optional)</Label>
            <Input
              id="cancel-reason"
              maxLength={255}
              placeholder="Ordered the wrong size"
              value={cancelReason}
              onChange={(event) => setCancelReason(event.target.value)}
            />
          </div>

          <DialogFooter className="gap-2">
            <Button variant="ghost" onClick={() => setConfirmingCancel(false)}>
              Keep my order
            </Button>
            <Button
              variant="destructive"
              loading={cancelOrder.isPending}
              onClick={async () => {
                await cancelOrder.mutateAsync({
                  id: order.id,
                  reason: cancelReason.trim() || undefined,
                });
                setConfirmingCancel(false);
              }}
            >
              Cancel order
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="text-muted-foreground">{label}</span>
      <span className="text-right">{children}</span>
    </div>
  );
}

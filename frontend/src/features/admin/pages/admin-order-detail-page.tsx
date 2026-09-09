import { ArrowLeft, Copy, User } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { ErrorState, LoadingState } from '@/components/common/states';
import { OrderStatusBadge, PaymentStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ShipmentPanel } from '@/features/admin/components/shipment-panel';
import { Separator } from '@/components/ui/separator';
import { OrderItemsTable } from '@/features/order/components/order-items-table';
import { OrderTimeline } from '@/features/order/components/order-timeline';
import { useOrderPolling } from '@/features/order/hooks';
import { OrderTotals } from '@/features/order/components/order-totals';
import { deliveryWindow, SHIPPING_METHOD_LABELS } from '@/features/order/types';
import { usePaymentByOrder } from '@/features/payment/hooks';
import { PAYMENT_METHOD_LABELS } from '@/features/payment/types';
import { useCopyToClipboard } from '@/hooks/use-copy';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { formatDateTime, humanize } from '@/utils/format';

/**
 * The operator's view of one order.
 *
 * Shows everything the customer view does, plus the identifiers support actually needs: the raw
 * order id, the customer id, the acquirer transaction reference. Each is copyable, because the
 * usual next step is pasting one into a log query.
 */
export default function AdminOrderDetailPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const { data: order, isLoading, isError, error, refetch, isSagaRunning } =
    useOrderPolling(orderId);
  const { copy } = useCopyToClipboard();

  const payment = usePaymentByOrder(orderId, Boolean(order && order.status !== 'CREATED'));

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
    <div>
      <PageHeader
        title={order.orderNumber}
        description={`Placed ${formatDateTime(order.createdAt)}`}
        breadcrumbs={[
          { label: 'Admin' },
          { label: 'Orders', to: paths.admin.orders },
          { label: order.orderNumber },
        ]}
        actions={
          <>
            <OrderStatusBadge status={order.status} />
            <Button variant="outline" size="sm" asChild>
              <Link to={paths.admin.orders}>
                <ArrowLeft aria-hidden />
                Back
              </Link>
            </Button>
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        <div className="space-y-6">
          <Card>
            <CardHeader className="flex-row items-center justify-between space-y-0">
              <CardTitle className="text-base">Saga progress</CardTitle>
              {isSagaRunning ? (
                <span className="flex items-center gap-2 text-xs text-muted-foreground">
                  <span className="h-2 w-2 animate-pulse rounded-full bg-primary" aria-hidden />
                  Live
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

          {/*
            Fulfilment sits under the order rather than on its own screen, because a parcel is
            only ever meaningful next to what is in it and where it is going. The queue at
            /admin/shipments is the same data arranged for a warehouse working through it.
          */}
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Fulfilment</CardTitle>
            </CardHeader>
            <CardContent>
              <ShipmentPanel orderId={order.id} orderStatus={order.status} />
            </CardContent>
          </Card>
        </div>

        <div className="space-y-6">
          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <User className="h-4 w-4 text-muted-foreground" aria-hidden />
                Customer
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <IdRow label="Email" value={order.userEmail} onCopy={copy} />
              <IdRow label="User id" value={order.userId} mono onCopy={copy} />
              <Separator />
              <div>
                <p className="text-muted-foreground">Delivering to</p>
                <p className="mt-1">{order.shippingAddress}</p>
                {order.delivery?.method ? (
                  <p className="mt-1 text-xs text-muted-foreground">
                    {SHIPPING_METHOD_LABELS[order.delivery.method]}
                    {order.destination?.countryCode ? ` to ${order.destination.countryCode}` : ''}
                  </p>
                ) : null}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Payment</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              {payment.data ? (
                <>
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Status</span>
                    <PaymentStatusBadge status={payment.data.status} showIcon={false} />
                  </div>
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Method</span>
                    <span>{PAYMENT_METHOD_LABELS[payment.data.method]}</span>
                  </div>
                  <IdRow label="Payment id" value={payment.data.id} mono onCopy={copy} />
                  {payment.data.transactionId ? (
                    <IdRow
                      label="Acquirer ref"
                      value={payment.data.transactionId}
                      mono
                      onCopy={copy}
                    />
                  ) : null}
                  {payment.data.failureReason ? (
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">Reason</span>
                      <span className="text-destructive">
                        {humanize(payment.data.failureReason)}
                      </span>
                    </div>
                  ) : null}
                  {payment.data.processedAt ? (
                    <div className="flex items-center justify-between">
                      <span className="text-muted-foreground">Processed</span>
                      <span>{formatDateTime(payment.data.processedAt)}</span>
                    </div>
                  ) : null}
                </>
              ) : (
                <p className="text-muted-foreground">
                  {order.status === 'CREATED'
                    ? 'Payment starts once stock is reserved.'
                    : 'No payment row for this order.'}
                </p>
              )}

              <Separator />

              {/* Support answers "why was I charged this?" from this panel, so it shows the
                  components rather than the answer. */}
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

          <Card>
            <CardHeader>
              <CardTitle className="text-base">Identifiers</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3 text-sm">
              <IdRow label="Order id" value={order.id} mono onCopy={copy} />
              <IdRow label="Order number" value={order.orderNumber} mono onCopy={copy} />
              <div className="flex items-center justify-between">
                <span className="text-muted-foreground">Last updated</span>
                <span>{formatDateTime(order.updatedAt)}</span>
              </div>
              {order.completedAt ? (
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Completed</span>
                  <span>{formatDateTime(order.completedAt)}</span>
                </div>
              ) : null}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

function IdRow({
  label,
  value,
  mono,
  onCopy,
}: {
  label: string;
  value: string;
  mono?: boolean;
  onCopy: (text: string, label?: string) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-2">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <button
        type="button"
        onClick={() => onCopy(value, `${label} copied`)}
        className="group flex min-w-0 items-center gap-1.5 text-right"
        title={value}
      >
        <span className={mono ? 'truncate font-mono text-xs' : 'truncate'}>{value}</span>
        <Copy className="h-3 w-3 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100" />
      </button>
    </div>
  );
}

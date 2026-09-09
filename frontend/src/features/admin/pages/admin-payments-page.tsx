import { CreditCard, Info, Search } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { EmptyState, ErrorState } from '@/components/common/states';
import { PaymentStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { usePaymentByOrder } from '@/features/payment/hooks';
import { PAYMENT_METHOD_LABELS } from '@/features/payment/types';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { formatDateTime, formatMoney, humanize } from '@/utils/format';

/**
 * Payment management.
 *
 * Payment Service exposes only `GET /api/payments/{id}` and `/order/{orderId}` — there is no list
 * endpoint, so a browsable table is impossible without either adding one or fanning out one
 * request per order, which would be worse. What works today is lookup by order id, so that is
 * what this screen does, and it says why.
 *
 * The order list already shows payment outcome per order, which covers the browsing case.
 */
export default function AdminPaymentsPage() {
  const [input, setInput] = useState('');
  const [orderId, setOrderId] = useState('');

  const query = usePaymentByOrder(orderId || undefined, Boolean(orderId));

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    setOrderId(input.trim());
  };

  return (
    <div className="mx-auto max-w-3xl">
      <PageHeader
        title="Payments"
        description="Look up the payment recorded against an order."
        breadcrumbs={[{ label: 'Admin' }, { label: 'Payments' }]}
      />

      <div className="space-y-6">
        <div className="flex gap-3 rounded-lg border bg-muted/40 p-4">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
          <div className="text-sm text-muted-foreground">
            <p>
              Payment Service has no list endpoint — only lookup by payment id or order id. A
              browsable table would need{' '}
              <code className="font-mono text-xs">GET /api/payments</code> with paging and a status
              filter.
            </p>
            <p className="mt-2">
              To browse instead, use{' '}
              <Link
                to={paths.admin.orders}
                className="font-medium text-foreground underline underline-offset-4"
              >
                Orders
              </Link>{' '}
              — each row shows its payment outcome.
            </p>
          </div>
        </div>

        <Card>
          <CardHeader>
            <CardTitle className="text-base">Find a payment</CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleSubmit} className="flex flex-col gap-3 sm:flex-row sm:items-end">
              <div className="flex-1">
                <Label htmlFor="orderId">Order id</Label>
                <Input
                  id="orderId"
                  className="mt-2 font-mono text-sm"
                  placeholder="3fa85f64-5717-4562-b3fc-2c963f66afa6"
                  value={input}
                  onChange={(event) => setInput(event.target.value)}
                />
              </div>
              <Button type="submit" disabled={!input.trim()}>
                <Search aria-hidden />
                Look up
              </Button>
            </form>
          </CardContent>
        </Card>

        {orderId ? (
          query.isLoading ? (
            <Skeleton className="h-64 rounded-lg" />
          ) : query.isError ? (
            normalizeError(query.error).status === 404 ? (
              <EmptyState
                icon={<CreditCard className="h-10 w-10" />}
                title="No payment for that order"
                description="Either the order id is wrong, or its saga has not reached the payment step yet."
              />
            ) : (
              <ErrorState
                error={normalizeError(query.error)}
                onRetry={() => void query.refetch()}
              />
            )
          ) : query.data ? (
            <Card>
              <CardHeader className="flex-row items-center justify-between space-y-0">
                <CardTitle className="text-base">Payment</CardTitle>
                <PaymentStatusBadge status={query.data.status} />
              </CardHeader>
              <CardContent className="space-y-3 text-sm">
                <Row label="Payment id" value={query.data.id} mono />
                <Row label="Order id" value={query.data.orderId} mono />
                {query.data.orderNumber ? (
                  <Row label="Order number" value={query.data.orderNumber} mono />
                ) : null}
                <Separator />
                <Row label="Method" value={PAYMENT_METHOD_LABELS[query.data.method]} />
                <div className="flex items-center justify-between">
                  <span className="text-muted-foreground">Amount</span>
                  <span className="font-semibold tabular">
                    {formatMoney(query.data.amount, query.data.currency)}
                  </span>
                </div>
                {query.data.transactionId ? (
                  <Row label="Acquirer reference" value={query.data.transactionId} mono />
                ) : null}
                {query.data.failureReason ? (
                  <div className="flex items-center justify-between">
                    <span className="text-muted-foreground">Failure reason</span>
                    <span className="text-destructive">{humanize(query.data.failureReason)}</span>
                  </div>
                ) : null}
                <Separator />
                <Row label="Created" value={formatDateTime(query.data.createdAt)} />
                {query.data.processedAt ? (
                  <Row label="Processed" value={formatDateTime(query.data.processedAt)} />
                ) : null}

                <Button variant="outline" size="sm" className="mt-2" asChild>
                  <Link to={paths.admin.order(query.data.orderId)}>View the order</Link>
                </Button>
              </CardContent>
            </Card>
          ) : null
        ) : null}
      </div>
    </div>
  );
}

function Row({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <span className="shrink-0 text-muted-foreground">{label}</span>
      <span className={mono ? 'truncate font-mono text-xs' : 'truncate'} title={value}>
        {value}
      </span>
    </div>
  );
}

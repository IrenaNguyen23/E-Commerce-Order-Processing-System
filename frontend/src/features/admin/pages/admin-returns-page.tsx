import { AlertTriangle, PackageOpen } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { Pagination } from '@/components/common/pagination';
import { EmptyState, ErrorState } from '@/components/common/states';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import {
  RETURN_QUEUE_STATUSES,
  RETURN_STATUS_LABELS,
  useApproveReturn,
  useMarkReturnReceived,
  useRefundReturn,
  useRejectReturn,
  useReturnQueue,
  type ReturnRequest,
  type ReturnStatus,
} from '@/features/order/returns-api';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';
import { formatDateTime, formatMoney } from '@/utils/format';

/**
 * Working through returns.
 *
 * <h2>Money follows the goods, and the screen enforces the order</h2>
 *
 * Each state offers exactly the action that belongs to it: a request can be approved or refused, an
 * approved one can be marked as arrived, an arrived one can be refunded. There is no way to refund
 * something that has not come back, because that is how a shop pays for goods it never receives.
 *
 * <h2>A failed refund is loud</h2>
 *
 * When a refund fails the return goes back to `RECEIVED` with the provider's reason on it, and this
 * screen shows that reason in red next to the retry. The alternative — a quiet return to the queue
 * — means the customer is owed money and nobody knows why it did not go.
 *
 * <h2>Refusing asks for a reason</h2>
 *
 * The note is the only place the customer finds out why. It is not mandatory, because obvious
 * abuse does not need explaining and forcing a note there produces one word typed a hundred times.
 */
export default function AdminReturnsPage() {
  const [status, setStatus] = useState<ReturnStatus | 'ALL'>('REQUESTED');
  const [page, setPage] = useState(0);

  const queue = useReturnQueue(status, page);
  const requests = queue.data?.content ?? [];

  function choose(next: ReturnStatus | 'ALL') {
    setStatus(next);
    setPage(0);
  }

  return (
    <div>
      <PageHeader
        title="Returns"
        description="Goods coming back, and the money going with them."
        breadcrumbs={[{ label: 'Admin', to: paths.admin.dashboard }, { label: 'Returns' }]}
      />

      <div className="mb-6 flex flex-wrap gap-2" role="tablist" aria-label="Return status">
        {(['ALL', ...RETURN_QUEUE_STATUSES] as const).map((option) => (
          <button
            key={option}
            type="button"
            role="tab"
            aria-selected={option === status}
            onClick={() => choose(option)}
            className={cn(
              'rounded-full border px-3 py-1 text-sm font-medium transition-colors',
              option === status
                ? 'border-transparent bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:bg-muted',
            )}
          >
            {option === 'ALL' ? 'Everything' : RETURN_STATUS_LABELS[option]}
          </button>
        ))}
      </div>

      {queue.isError ? (
        <ErrorState error={normalizeError(queue.error)} onRetry={() => void queue.refetch()} />
      ) : queue.isLoading ? (
        <div className="space-y-3">
          {[0, 1, 2].map((row) => (
            <Skeleton key={row} className="h-32 w-full rounded-lg" />
          ))}
        </div>
      ) : requests.length === 0 ? (
        <EmptyState
          icon={<PackageOpen className="h-10 w-10" />}
          title="Nothing here"
          description={
            status === 'REQUESTED'
              ? 'No return is waiting for a decision. New requests appear here as customers raise them.'
              : 'No return is in this state right now.'
          }
        />
      ) : (
        <>
          <p className="mb-4 text-sm text-muted-foreground">
            {queue.data?.totalElements} {queue.data?.totalElements === 1 ? 'return' : 'returns'}
          </p>

          <ul className="space-y-3">
            {requests.map((request) => (
              <li key={request.id}>
                <ReturnRow request={request} />
              </li>
            ))}
          </ul>

          {queue.data && queue.data.totalPages > 1 ? (
            <div className="mt-6">
              <Pagination
                page={page}
                totalPages={queue.data.totalPages}
                pageSize={queue.data.size}
                totalElements={queue.data.totalElements}
                onPageChange={setPage}
              />
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------
// One return
// ---------------------------------------------------------------------------------------------

function ReturnRow({ request }: { request: ReturnRequest }) {
  const approve = useApproveReturn();
  const reject = useRejectReturn();
  const received = useMarkReturnReceived();
  const refund = useRefundReturn();

  const [note, setNote] = useState('');
  const busy =
    approve.isPending || reject.isPending || received.isPending || refund.isPending;

  return (
    <Card>
      <CardContent className="space-y-3 py-4">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <Link
                to={paths.admin.order(request.orderId)}
                className="font-medium hover:underline"
              >
                {request.orderNumber}
              </Link>
              <Badge variant="outline">{RETURN_STATUS_LABELS[request.status]}</Badge>
              {request.refundShipping ? (
                <Badge variant="secondary">delivery included</Badge>
              ) : null}
            </div>

            <p className="text-sm">{request.reason}</p>

            <ul className="text-xs text-muted-foreground">
              {request.items.map((item) => (
                <li key={item.orderItemId}>
                  {item.quantity} × {item.productName} ({item.sku}) —{' '}
                  {formatMoney(item.refundAmount, request.currency)}
                </li>
              ))}
            </ul>

            <p className="text-xs text-muted-foreground">
              Requested {formatDateTime(request.requestedAt)}
              {request.receivedAt ? ` · received ${formatDateTime(request.receivedAt)}` : null}
              {request.restocked === true ? ' · back on sale' : null}
              {request.restocked === false ? ' · written off' : null}
              {request.refundReference ? ` · ${request.refundReference}` : null}
            </p>
          </div>

          <span className="shrink-0 text-right font-medium tabular-nums">
            {formatMoney(request.refundAmount, request.currency)}
          </span>
        </div>

        {/*
          A refund that did not go through. Shown here rather than only in a log, because the
          customer is owed money and this is the screen somebody is actually looking at.
        */}
        {request.refundFailure ? (
          <div className="flex items-start gap-2 rounded border border-destructive bg-destructive/10 p-3 text-sm">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-destructive" aria-hidden />
            <div>
              <p className="font-medium">The last refund attempt failed.</p>
              <p className="text-muted-foreground">{request.refundFailure}</p>
            </div>
          </div>
        ) : null}

        {request.status === 'REQUESTED' ? (
          <div className="space-y-2">
            <Input
              placeholder="A note for the customer — the only place they find out why"
              value={note}
              onChange={(event) => setNote(event.target.value)}
            />
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                disabled={busy}
                onClick={() => approve.mutate({ returnId: request.id, note: note || undefined })}
              >
                Approve
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={busy}
                onClick={() => reject.mutate({ returnId: request.id, note: note || undefined })}
              >
                Refuse
              </Button>
            </div>
          </div>
        ) : null}

        {/*
          Two buttons rather than one plus a checkbox. Receiving goods and deciding whether they
          can be sold again is one moment — the person has the box open — and a checkbox next to a
          single button is a thing people click past without reading.
        */}
        {request.status === 'APPROVED' ? (
          <div className="space-y-2">
            <p className="text-sm font-medium">The goods have arrived. Can they be sold again?</p>
            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                disabled={busy}
                onClick={() => received.mutate({ returnId: request.id, restock: true })}
              >
                Back on sale
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() => received.mutate({ returnId: request.id, restock: false })}
              >
                Received, but write off
              </Button>
            </div>
            <p className="text-xs text-muted-foreground">
              &ldquo;Back on sale&rdquo; puts exactly these units back into the building they
              shipped from. Write off records them as here and not for sale — a cracked screen
              should never reach the next customer.
            </p>
          </div>
        ) : null}

        {request.status === 'RECEIVED' ? (
          <Button size="sm" disabled={busy} onClick={() => refund.mutate(request.id)}>
            {request.refundFailure ? 'Try the refund again' : 'Refund'}{' '}
            {formatMoney(request.refundAmount, request.currency)}
          </Button>
        ) : null}

        {request.status === 'REFUND_PENDING' ? (
          <p className="text-xs text-muted-foreground">
            The refund has been sent to the payment provider. This becomes{' '}
            <strong>Refunded</strong> when it confirms — pressing anything again would not send it
            twice, but there is nothing to press.
          </p>
        ) : null}
      </CardContent>
    </Card>
  );
}

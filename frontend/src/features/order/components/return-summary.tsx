import { RotateCcw } from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { ReturnDialog } from '@/features/order/components/return-dialog';
import {
  RETURN_STATUS_LABELS,
  useCancelReturn,
  useOrderReturns,
  type ReturnStatus,
} from '@/features/order/returns-api';
import { formatDateTime, formatMoney } from '@/utils/format';

/**
 * Returns on the customer's own order page.
 *
 * <h2>Nothing is shown until there is something to say</h2>
 *
 * An order with no returns renders the button and no card. A "Returns (0)" panel on every order
 * is noise on the ninety-nine that will never have one.
 *
 * <h2>Each state says what happens next</h2>
 *
 * A status word alone leaves the customer guessing. `APPROVED` means "post it back", `RECEIVED`
 * means "we have it, the money is coming", `REFUND_PENDING` means "it is on its way to your bank".
 * Those are different questions to a support desk, and answering them here is cheaper than
 * answering them by email.
 */

const WHAT_HAPPENS_NEXT: Record<ReturnStatus, string> = {
  REQUESTED: 'We are looking at this. You do not need to do anything yet.',
  APPROVED: 'Post the items back to us. We will email you the address.',
  REJECTED: 'This request was not accepted.',
  RECEIVED: 'Your items are back with us. The refund is being arranged.',
  REFUND_PENDING: 'The refund has been sent. Card refunds take a few days to appear.',
  REFUNDED: 'Refunded. It may take a few days to show on your statement.',
  CANCELLED: 'You called this off.',
};

const TONE: Record<ReturnStatus, 'default' | 'secondary' | 'success' | 'warning' | 'destructive'> = {
  REQUESTED: 'secondary',
  APPROVED: 'default',
  REJECTED: 'destructive',
  RECEIVED: 'default',
  REFUND_PENDING: 'warning',
  REFUNDED: 'success',
  CANCELLED: 'secondary',
};

export function ReturnSummary({
  orderId,
  orderStatus,
}: {
  orderId: string;
  orderStatus: string;
}) {
  const returns = useOrderReturns(orderId);
  const cancelReturn = useCancelReturn();
  const [dialogOpen, setDialogOpen] = useState(false);

  const requests = returns.data ?? [];

  // Only a finished order can be returned. One still being paid for is cancelled instead, which
  // is a different button somewhere else on this page.
  const canReturn = orderStatus === 'COMPLETED';

  if (!canReturn && requests.length === 0) {
    return null;
  }

  return (
    <>
      <Card>
        <CardHeader className="flex-row items-center justify-between space-y-0">
          <CardTitle className="flex items-center gap-2 text-base">
            <RotateCcw className="h-4 w-4 text-muted-foreground" aria-hidden />
            Returns
          </CardTitle>
          {canReturn ? (
            <Button variant="outline" size="sm" onClick={() => setDialogOpen(true)}>
              Return items
            </Button>
          ) : null}
        </CardHeader>

        <CardContent className="space-y-4">
          {requests.length === 0 ? (
            <p className="text-sm text-muted-foreground">
              Changed your mind, or something arrived faulty? Start here and we will tell you
              where to send it.
            </p>
          ) : (
            <ul className="space-y-4">
              {requests.map((request) => (
                <li key={request.id} className="space-y-2 rounded-lg border p-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <Badge variant={TONE[request.status]}>
                      {RETURN_STATUS_LABELS[request.status]}
                    </Badge>
                    <span className="text-sm font-medium tabular-nums">
                      {formatMoney(request.refundAmount, request.currency)}
                    </span>
                  </div>

                  <p className="text-sm text-muted-foreground">
                    {WHAT_HAPPENS_NEXT[request.status]}
                  </p>

                  <ul className="text-xs text-muted-foreground">
                    {request.items.map((item) => (
                      <li key={item.orderItemId}>
                        {item.quantity} × {item.productName}
                      </li>
                    ))}
                  </ul>

                  {request.decisionNote ? (
                    <p className="text-xs">
                      <span className="text-muted-foreground">Our note: </span>
                      {request.decisionNote}
                    </p>
                  ) : null}

                  <p className="text-xs text-muted-foreground">
                    Requested {formatDateTime(request.requestedAt)}
                    {request.refundedAt
                      ? ` · refunded ${formatDateTime(request.refundedAt)}`
                      : null}
                  </p>

                  {request.status === 'REQUESTED' ? (
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={cancelReturn.isPending}
                      onClick={() => cancelReturn.mutate(request.id)}
                    >
                      Call it off
                    </Button>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <ReturnDialog orderId={orderId} open={dialogOpen} onOpenChange={setDialogOpen} />
    </>
  );
}

import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';

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
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { useCreateReturn, useReturnableLines } from '@/features/order/returns-api';
import { paths } from '@/routes/paths';
import { formatMoney } from '@/utils/format';

/**
 * Choosing what to send back.
 *
 * <h2>The form cannot offer what would be refused</h2>
 *
 * What is still returnable is fetched before the dialog renders, and each line is capped at that
 * number. A line already fully returned is shown greyed rather than hidden — somebody looking for
 * it needs to see that it is accounted for, not wonder where it went.
 *
 * <h2>The refund is shown before the customer commits</h2>
 *
 * Every line says what it gives back, and the total updates as quantities change. A return form
 * that hides the figure until afterwards produces a support ticket from everybody who expected
 * the shelf price and got what they actually paid after a discount.
 *
 * <p>What it deliberately does not promise is the delivery charge. That only comes back when the
 * whole order does, and predicting it here would mean duplicating a rule that lives on the server.
 * The confirmed return says what it will be.
 */
export function ReturnDialog({
  orderId,
  open,
  onOpenChange,
}: {
  orderId: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const lines = useReturnableLines(orderId, open);
  const createReturn = useCreateReturn();

  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [reason, setReason] = useState('');

  // Memoised rather than `lines.data ?? []` inline: a fresh empty array on every render would
  // re-run the estimate below every time anything at all changes.
  const available = useMemo(() => lines.data ?? [], [lines.data]);
  const currency = available[0]?.currency ?? 'EUR';

  const chosen = useMemo(
    () => Object.entries(quantities).filter(([, quantity]) => quantity > 0),
    [quantities],
  );

  const estimate = useMemo(
    () =>
      chosen.reduce((total, [orderItemId, quantity]) => {
        const line = available.find((item) => item.orderItemId === orderItemId);
        return total + (line ? line.refundPerUnit * quantity : 0);
      }, 0),
    [chosen, available],
  );

  function submit() {
    createReturn.mutate(
      {
        orderId,
        payload: {
          items: chosen.map(([orderItemId, quantity]) => ({ orderItemId, quantity })),
          reason: reason.trim(),
        },
      },
      {
        onSuccess: () => {
          setQuantities({});
          setReason('');
          onOpenChange(false);
        },
      },
    );
  }

  const nothingLeft = available.length > 0 && available.every((l) => l.returnableQuantity === 0);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>Return items</DialogTitle>
        </DialogHeader>

        {lines.isLoading ? (
          <div className="space-y-3">
            {[0, 1].map((row) => (
              <Skeleton key={row} className="h-14 w-full rounded" />
            ))}
          </div>
        ) : nothingLeft ? (
          <p className="text-sm text-muted-foreground">
            Everything on this order has already been returned or is part of an open request.
          </p>
        ) : (
          <div className="space-y-4">
            <ul className="space-y-3">
              {available.map((line) => {
                const disabled = line.returnableQuantity === 0;
                return (
                  <li
                    key={line.orderItemId}
                    className="flex items-center justify-between gap-4 text-sm"
                  >
                    <div className="min-w-0">
                      <p className={disabled ? 'text-muted-foreground line-through' : ''}>
                        {line.productName}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {formatMoney(line.refundPerUnit, line.currency)} each
                        {line.alreadyReturned > 0
                          ? ` · ${line.alreadyReturned} of ${line.orderedQuantity} already returned`
                          : null}
                      </p>
                    </div>

                    <div className="flex shrink-0 items-center gap-2">
                      <Label htmlFor={`qty-${line.orderItemId}`} className="sr-only">
                        Quantity to return of {line.productName}
                      </Label>
                      <Input
                        id={`qty-${line.orderItemId}`}
                        type="number"
                        min={0}
                        max={line.returnableQuantity}
                        disabled={disabled}
                        className="w-20"
                        value={quantities[line.orderItemId] ?? 0}
                        onChange={(event) => {
                          // Clamped here as well as by the input's max, because a typed value
                          // bypasses the spinner and the server would refuse the whole request
                          // over one line.
                          const wanted = Number(event.target.value);
                          const safe = Number.isNaN(wanted)
                            ? 0
                            : Math.min(Math.max(0, wanted), line.returnableQuantity);
                          setQuantities((current) => ({
                            ...current,
                            [line.orderItemId]: safe,
                          }));
                        }}
                      />
                      <span className="text-xs text-muted-foreground">
                        / {line.returnableQuantity}
                      </span>
                    </div>
                  </li>
                );
              })}
            </ul>

            <div className="space-y-2">
              <Label htmlFor="return-reason">Why are you sending it back?</Label>
              <Textarea
                id="return-reason"
                rows={3}
                maxLength={500}
                placeholder="It arrived with a cracked screen"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
              <p className="text-xs text-muted-foreground">
                A faulty item and a change of mind are handled differently, so it is worth saying
                which.
              </p>
            </div>

            <div className="rounded-lg border bg-muted/40 p-3 text-sm">
              <div className="flex items-center justify-between">
                <span>Items coming back</span>
                <span className="font-medium tabular-nums">
                  {formatMoney(estimate, currency)}
                </span>
              </div>
              <p className="mt-1 text-xs text-muted-foreground">
                Delivery is refunded as well when the whole order comes back. See the{' '}
                <Link to={paths.returns} className="underline">
                  returns policy
                </Link>
                .
              </p>
            </div>
          </div>
        )}

        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button
            onClick={submit}
            disabled={chosen.length === 0 || reason.trim().length === 0 || createReturn.isPending}
          >
            {createReturn.isPending ? 'Sending…' : 'Request return'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

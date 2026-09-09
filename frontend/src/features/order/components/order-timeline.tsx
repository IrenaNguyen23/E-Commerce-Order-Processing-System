import { AlertCircle, Check, Loader2 } from 'lucide-react';

import { cn } from '@/utils/cn';

import { SAGA_STEPS, stepStateFor, type Order, type StepState } from '../types';

/**
 * The saga, rendered as a progress timeline.
 *
 * This is the payoff of an asynchronous backend: instead of a spinner and a promise, the customer
 * watches stock reservation, payment and confirmation land one by one. When it compensates, the
 * step that failed is marked in place — so "cancelled" comes with an answer to *where*.
 */
export function OrderTimeline({ order, className }: { order: Order; className?: string }) {
  const cancelled = order.status === 'CANCELLED';

  return (
    <div className={className}>
      <ol className="relative space-y-0">
        {SAGA_STEPS.map((step, index) => {
          const state = stepStateFor(step, order);
          const isLast = index === SAGA_STEPS.length - 1;

          return (
            <li key={step.key} className="relative flex gap-4 pb-8 last:pb-0">
              {/* Connector. Drawn behind the marker and stopped at the last step so the line
                  does not dangle past the final node. */}
              {!isLast ? (
                <span
                  className={cn(
                    'absolute left-[15px] top-8 h-full w-px',
                    state === 'done' ? 'bg-primary' : 'bg-border',
                  )}
                  aria-hidden
                />
              ) : null}

              <StepMarker state={state} />

              <div className="min-w-0 flex-1 pt-1">
                <p
                  className={cn(
                    'text-sm font-medium',
                    state === 'pending' && 'text-muted-foreground',
                    state === 'failed' && 'text-destructive',
                  )}
                >
                  {step.label}
                </p>
                <p className="mt-0.5 text-sm text-muted-foreground">
                  {state === 'failed'
                    ? failureText(order)
                    : state === 'pending' && cancelled
                      ? 'Not reached'
                      : step.description}
                </p>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}

function StepMarker({ state }: { state: StepState }) {
  return (
    <span
      className={cn(
        'relative z-10 flex h-8 w-8 shrink-0 items-center justify-center rounded-full border-2',
        state === 'done' && 'border-primary bg-primary text-primary-foreground',
        state === 'active' && 'border-primary bg-background text-primary',
        state === 'failed' && 'border-destructive bg-destructive text-destructive-foreground',
        state === 'pending' && 'border-border bg-background text-muted-foreground',
      )}
      aria-hidden
    >
      {state === 'done' ? (
        <Check className="h-4 w-4" />
      ) : state === 'active' ? (
        <Loader2 className="h-4 w-4 animate-spin" />
      ) : state === 'failed' ? (
        <AlertCircle className="h-4 w-4" />
      ) : (
        <span className="h-2 w-2 rounded-full bg-current" />
      )}
    </span>
  );
}

/**
 * Turns the backend's machine reason into something a customer can act on.
 *
 * `INSUFFICIENT_FUNDS` is accurate but blunt; the wording here says what happened *and* what it
 * means for them — above all, that they have not been charged.
 */
function failureText(order: Order): string {
  const reason = order.failureReason ?? '';

  if (order.failedStep === 'INVENTORY') {
    return reason === 'OUT_OF_STOCK'
      ? 'One or more items sold out before we could reserve them. You have not been charged.'
      : 'We could not reserve stock for this order. You have not been charged.';
  }

  if (order.failedStep === 'PAYMENT') {
    return reason === 'INSUFFICIENT_FUNDS'
      ? 'The payment was declined. Anything reserved has been released and you have not been charged.'
      : 'The payment could not be completed. Anything reserved has been released.';
  }

  return 'This step did not complete.';
}

import { AlertCircle, Inbox, Loader2, RefreshCw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';

import { Button } from '@/components/ui/button';
import type { AppError } from '@/types/api';
import { cn } from '@/utils/cn';

/**
 * The four states every data-driven screen must handle.
 *
 * Having them as shared components is what stops each page inventing its own — and, more to the
 * point, stops pages quietly skipping the empty and error cases.
 */

// ---------------------------------------------------------------------------------------------

interface EmptyStateProps {
  icon?: ReactNode;
  title: string;
  /** Say what to do next, not just that there is nothing here. */
  description?: string;
  action?: ReactNode;
  className?: string;
}

export function EmptyState({ icon, title, description, action, className }: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center rounded-lg border border-dashed px-6 py-16 text-center',
        className,
      )}
    >
      <div className="mb-4 text-muted-foreground" aria-hidden>
        {icon ?? <Inbox className="h-10 w-10" />}
      </div>
      <h3 className="text-base font-semibold">{title}</h3>
      {description ? (
        <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>
      ) : null}
      {action ? <div className="mt-6">{action}</div> : null}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------

interface ErrorStateProps {
  error: AppError | null;
  onRetry?: () => void;
  title?: string;
  className?: string;
}

/**
 * A failure the user can act on.
 *
 * The correlation id is shown deliberately: it is the one string that lets support trace the
 * whole saga across six services in the backend logs.
 */
export function ErrorState({ error, onRetry, title, className }: ErrorStateProps) {
  const isOffline = error?.status === 0;

  return (
    <div
      role="alert"
      className={cn(
        'flex flex-col items-center justify-center rounded-lg border border-destructive/30 bg-destructive/5 px-6 py-16 text-center',
        className,
      )}
    >
      <div className="mb-4 text-destructive" aria-hidden>
        {isOffline ? <WifiOff className="h-10 w-10" /> : <AlertCircle className="h-10 w-10" />}
      </div>

      <h3 className="text-base font-semibold">
        {title ?? (isOffline ? 'Cannot reach the server' : 'Something went wrong')}
      </h3>

      <p className="mt-1 max-w-md text-sm text-muted-foreground">
        {error?.message ?? 'An unexpected error occurred.'}
      </p>

      {error?.correlationId ? (
        <p className="mt-3 font-mono text-xs text-muted-foreground">
          Reference: {error.correlationId}
        </p>
      ) : null}

      {onRetry ? (
        <Button variant="outline" className="mt-6" onClick={onRetry}>
          <RefreshCw aria-hidden />
          Try again
        </Button>
      ) : null}
    </div>
  );
}

// ---------------------------------------------------------------------------------------------

interface LoadingStateProps {
  label?: string;
  className?: string;
}

/**
 * A spinner, for the rare case where a skeleton cannot work — a full-page route transition, or
 * an action whose result has no shape to stand in for. Lists use skeletons instead.
 */
export function LoadingState({ label = 'Loading…', className }: LoadingStateProps) {
  return (
    <div
      className={cn('flex flex-col items-center justify-center gap-3 py-16', className)}
      role="status"
      aria-live="polite"
    >
      <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" aria-hidden />
      <span className="text-sm text-muted-foreground">{label}</span>
    </div>
  );
}

/** Fills the viewport. Used as the Suspense fallback for lazy routes. */
export function FullPageLoader({ label }: { label?: string }) {
  return (
    <div className="flex min-h-[60vh] items-center justify-center">
      <LoadingState label={label} />
    </div>
  );
}

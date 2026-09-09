import { Info, TrendingUp } from 'lucide-react';

import { PageHeader } from '@/components/common/page-header';
import { ErrorState } from '@/components/common/states';
import { OrderStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';
import { formatMoney, formatNumber, humanize } from '@/utils/format';

import { useAdminMetrics } from '../hooks';

/**
 * Reporting.
 *
 * Everything here is derived client-side from the order list, because Order Service has no
 * aggregate endpoint. That makes some figures exact and others a sample, and the difference is
 * labelled per figure rather than glossed over — a dashboard that silently mixes the two is worse
 * than no dashboard.
 *
 * Exact: status counts (each is a `totalElements` from a filtered query).
 * Sampled: revenue and averages (computed over the most recent 200 orders).
 */
export default function AdminReportsPage() {
  const { metrics, isLoading, error, revenueSampleCount, refetch } = useAdminMetrics(200);

  if (error) {
    return (
      <div>
        <PageHeader title="Reports" />
        <ErrorState error={normalizeError(error)} onRetry={refetch} />
      </div>
    );
  }

  const failureRate =
    metrics && metrics.completedOrders + metrics.cancelledOrders > 0
      ? metrics.cancelledOrders / (metrics.completedOrders + metrics.cancelledOrders)
      : undefined;

  return (
    <div>
      <PageHeader
        title="Reports"
        description="Order and revenue figures derived from the order list."
        breadcrumbs={[{ label: 'Admin' }, { label: 'Reports' }]}
        actions={
          <Button variant="outline" onClick={refetch} disabled={isLoading}>
            Refresh
          </Button>
        }
      />

      <div className="space-y-6">
        <div className="flex gap-3 rounded-lg border bg-muted/40 p-4">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
          <div className="text-sm text-muted-foreground">
            <p>
              These figures are computed in the browser from{' '}
              <code className="font-mono text-xs">GET /api/orders</code>. Status counts are exact;
              revenue is a sample of the most recent 200 orders.
            </p>
            <p className="mt-2">
              Real reporting needs an aggregate endpoint — something like{' '}
              <code className="font-mono text-xs">GET /api/orders/stats?from=&amp;to=</code>{' '}
              returning totals, revenue and a time series computed in the database.
            </p>
          </div>
        </div>

        <div className="grid gap-6 lg:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Order outcomes</CardTitle>
              <p className="text-xs text-muted-foreground">Exact counts</p>
            </CardHeader>
            <CardContent className="space-y-4">
              {isLoading || !metrics ? (
                <div className="space-y-3">
                  {Array.from({ length: 5 }, (_, index) => (
                    <Skeleton key={index} className="h-8" />
                  ))}
                </div>
              ) : (
                <>
                  {metrics.breakdown.map((entry) => (
                    <div key={entry.status}>
                      <div className="mb-1.5 flex items-center justify-between text-sm">
                        <OrderStatusBadge status={entry.status} showIcon={false} />
                        <span className="text-muted-foreground tabular">
                          {formatNumber(entry.count)} · {Math.round(entry.share * 100)}%
                        </span>
                      </div>
                      <div className="h-2 overflow-hidden rounded-full bg-muted">
                        <div
                          className={cn(
                            'h-full rounded-full',
                            entry.status === 'COMPLETED' && 'bg-success',
                            entry.status === 'CANCELLED' && 'bg-destructive',
                            !['COMPLETED', 'CANCELLED'].includes(entry.status) && 'bg-primary',
                          )}
                          style={{
                            width: `${Math.max(entry.share * 100, entry.count > 0 ? 2 : 0)}%`,
                          }}
                        />
                      </div>
                    </div>
                  ))}

                  <Separator />

                  <Stat
                    label="Total orders"
                    value={formatNumber(metrics.totalOrders)}
                    note="All statuses"
                  />
                  {failureRate !== undefined ? (
                    <Stat
                      label="Cancellation rate"
                      value={`${Math.round(failureRate * 100)}%`}
                      note="Cancelled ÷ terminal orders"
                      tone={failureRate > 0.2 ? 'warning' : undefined}
                    />
                  ) : null}
                </>
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <TrendingUp className="h-4 w-4 text-muted-foreground" aria-hidden />
                Revenue
              </CardTitle>
              <p className="text-xs text-muted-foreground">
                Sampled from the most recent 200 orders
              </p>
            </CardHeader>
            <CardContent className="space-y-4">
              {isLoading || !metrics ? (
                <div className="space-y-3">
                  {Array.from({ length: 4 }, (_, index) => (
                    <Skeleton key={index} className="h-10" />
                  ))}
                </div>
              ) : (
                <>
                  <Stat
                    label="Revenue"
                    value={formatMoney(metrics.revenue, metrics.currency)}
                    note={`Across ${revenueSampleCount} completed ${
                      revenueSampleCount === 1 ? 'order' : 'orders'
                    }`}
                    large
                  />
                  <Separator />
                  <Stat
                    label="Average order value"
                    value={formatMoney(metrics.averageOrderValue, metrics.currency)}
                    note="Completed orders only"
                  />
                  <Stat
                    label="Completed"
                    value={formatNumber(metrics.completedOrders)}
                    note="Exact count"
                  />
                  <Stat
                    label="In flight"
                    value={formatNumber(metrics.inFlightOrders)}
                    note="Sagas still running"
                  />
                </>
              )}
            </CardContent>
          </Card>
        </div>

        {metrics && metrics.cancelledOrders > 0 ? (
          <Card>
            <CardHeader>
              <CardTitle className="text-base">Where orders fail</CardTitle>
              <p className="text-xs text-muted-foreground">
                From the sampled orders that were cancelled
              </p>
            </CardHeader>
            <CardContent>
              <FailureBreakdown metrics={metrics} />
            </CardContent>
          </Card>
        ) : null}
      </div>
    </div>
  );
}

function FailureBreakdown({ metrics }: { metrics: NonNullable<ReturnType<typeof useAdminMetrics>['metrics']> }) {
  const cancelled = metrics.recentOrders.filter((order) => order.status === 'CANCELLED');

  if (cancelled.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No cancellations in the recent sample.
      </p>
    );
  }

  const byReason = new Map<string, number>();
  cancelled.forEach((order) => {
    const key = `${order.failedStep ?? 'UNKNOWN'} · ${order.failureReason ?? 'unspecified'}`;
    byReason.set(key, (byReason.get(key) ?? 0) + 1);
  });

  return (
    <ul className="space-y-2">
      {Array.from(byReason.entries())
        .sort((a, b) => b[1] - a[1])
        .map(([reason, count]) => (
          <li key={reason} className="flex items-center justify-between text-sm">
            <span>{humanize(reason.replace(' · ', ' — '))}</span>
            <span className="text-muted-foreground tabular">{count}</span>
          </li>
        ))}
    </ul>
  );
}

function Stat({
  label,
  value,
  note,
  large,
  tone,
}: {
  label: string;
  value: string;
  note?: string;
  large?: boolean;
  tone?: 'warning';
}) {
  return (
    <div>
      <p className="text-sm text-muted-foreground">{label}</p>
      <p
        className={cn(
          'font-semibold tabular',
          large ? 'text-2xl' : 'text-lg',
          tone === 'warning' && 'text-warning',
        )}
      >
        {value}
      </p>
      {note ? <p className="text-xs text-muted-foreground">{note}</p> : null}
    </div>
  );
}

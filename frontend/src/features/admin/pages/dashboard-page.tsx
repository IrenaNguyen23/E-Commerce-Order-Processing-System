import { Activity, CheckCircle2, Info, ShoppingBag, TrendingUp, XCircle } from 'lucide-react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { ErrorState } from '@/components/common/states';
import { OrderStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { cn } from '@/utils/cn';
import { formatDateTime, formatMoney, formatNumber } from '@/utils/format';

import { useAdminMetrics } from '../hooks';

export default function DashboardPage() {
  const { metrics, isLoading, error, revenueSampleCount, refetch } = useAdminMetrics();

  if (error) {
    return (
      <div>
        <PageHeader title="Dashboard" />
        <ErrorState error={normalizeError(error)} onRetry={refetch} />
      </div>
    );
  }

  return (
    <div>
      <PageHeader
        title="Dashboard"
        description="Order health across the platform."
        actions={
          <Button variant="outline" onClick={refetch} disabled={isLoading}>
            Refresh
          </Button>
        }
      />

      <div className="space-y-6">
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <MetricCard
            label="Total orders"
            value={metrics ? formatNumber(metrics.totalOrders) : undefined}
            icon={ShoppingBag}
            isLoading={isLoading}
          />
          <MetricCard
            label="Completed"
            value={metrics ? formatNumber(metrics.completedOrders) : undefined}
            icon={CheckCircle2}
            tone="success"
            isLoading={isLoading}
          />
          <MetricCard
            label="Cancelled"
            value={metrics ? formatNumber(metrics.cancelledOrders) : undefined}
            icon={XCircle}
            tone="destructive"
            isLoading={isLoading}
          />
          <MetricCard
            label="In flight"
            value={metrics ? formatNumber(metrics.inFlightOrders) : undefined}
            hint="Sagas still running"
            icon={Activity}
            isLoading={isLoading}
          />
        </div>

        <div className="grid gap-6 lg:grid-cols-3">
          <Card className="lg:col-span-2">
            <CardHeader>
              <CardTitle className="text-base">Order status</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {isLoading || !metrics ? (
                <div className="space-y-3">
                  {Array.from({ length: 5 }, (_, index) => (
                    <Skeleton key={index} className="h-8" />
                  ))}
                </div>
              ) : (
                metrics.breakdown.map((entry) => (
                  <div key={entry.status}>
                    <div className="mb-1.5 flex items-center justify-between text-sm">
                      <OrderStatusBadge status={entry.status} showIcon={false} />
                      <span className="text-muted-foreground tabular">
                        {formatNumber(entry.count)} ({Math.round(entry.share * 100)}%)
                      </span>
                    </div>
                    {/* A simple proportional bar rather than a charting library — one number per
                        row does not justify 40 kB of dependency. */}
                    <div className="h-2 overflow-hidden rounded-full bg-muted">
                      <div
                        className={cn(
                          'h-full rounded-full transition-all',
                          entry.status === 'COMPLETED' && 'bg-success',
                          entry.status === 'CANCELLED' && 'bg-destructive',
                          !['COMPLETED', 'CANCELLED'].includes(entry.status) && 'bg-primary',
                        )}
                        style={{ width: `${Math.max(entry.share * 100, entry.count > 0 ? 2 : 0)}%` }}
                      />
                    </div>
                  </div>
                ))
              )}
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle className="flex items-center gap-2 text-base">
                <TrendingUp className="h-4 w-4 text-muted-foreground" aria-hidden />
                Revenue
              </CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {isLoading || !metrics ? (
                <Skeleton className="h-16" />
              ) : (
                <>
                  <div>
                    <p className="text-2xl font-semibold tabular">
                      {formatMoney(metrics.revenue, metrics.currency)}
                    </p>
                    <p className="text-xs text-muted-foreground">
                      Across {revenueSampleCount} completed{' '}
                      {revenueSampleCount === 1 ? 'order' : 'orders'}
                    </p>
                  </div>

                  <div>
                    <p className="text-sm font-medium tabular">
                      {formatMoney(metrics.averageOrderValue, metrics.currency)}
                    </p>
                    <p className="text-xs text-muted-foreground">Average order value</p>
                  </div>

                  {metrics.conversionRate !== undefined ? (
                    <div>
                      <p className="text-sm font-medium tabular">
                        {Math.round(metrics.conversionRate * 100)}%
                      </p>
                      <p className="text-xs text-muted-foreground">
                        Completed vs. cancelled
                      </p>
                    </div>
                  ) : null}

                  {/* The limitation, stated where the number is read — not buried in a doc. */}
                  <p className="flex gap-2 rounded-md bg-muted/60 p-2 text-xs text-muted-foreground">
                    <Info className="mt-0.5 h-3 w-3 shrink-0" aria-hidden />
                    Derived from the most recent 100 orders. Order Service has no aggregate
                    endpoint, so this is a sample rather than a full ledger.
                  </p>
                </>
              )}
            </CardContent>
          </Card>
        </div>

        <Card>
          <CardHeader className="flex-row items-center justify-between space-y-0">
            <CardTitle className="text-base">Recent orders</CardTitle>
            <Button variant="ghost" size="sm" asChild>
              <Link to={paths.admin.orders}>View all</Link>
            </Button>
          </CardHeader>
          <CardContent>
            {isLoading || !metrics ? (
              <div className="space-y-2">
                {Array.from({ length: 5 }, (_, index) => (
                  <Skeleton key={index} className="h-12" />
                ))}
              </div>
            ) : metrics.recentOrders.length === 0 ? (
              <p className="py-8 text-center text-sm text-muted-foreground">No orders yet.</p>
            ) : (
              <div className="divide-y">
                {metrics.recentOrders.map((order) => (
                  <Link
                    key={order.id}
                    to={paths.admin.order(order.id)}
                    className="flex items-center justify-between gap-3 py-3 transition-colors hover:bg-accent/30"
                  >
                    <div className="min-w-0">
                      <p className="font-mono text-sm">{order.orderNumber}</p>
                      <p className="truncate text-xs text-muted-foreground">
                        {order.userEmail} · {formatDateTime(order.createdAt)}
                      </p>
                    </div>
                    <div className="flex shrink-0 items-center gap-3">
                      <span className="text-sm font-medium tabular">
                        {formatMoney(order.totalAmount, order.currency)}
                      </span>
                      <OrderStatusBadge status={order.status} showIcon={false} />
                    </div>
                  </Link>
                ))}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  );
}

function MetricCard({
  label,
  value,
  hint,
  icon: Icon,
  tone,
  isLoading,
}: {
  label: string;
  value?: string;
  hint?: string;
  icon: typeof ShoppingBag;
  tone?: 'success' | 'destructive';
  isLoading?: boolean;
}) {
  return (
    <Card>
      <CardContent className="p-5">
        <div className="flex items-center justify-between">
          <p className="text-sm text-muted-foreground">{label}</p>
          <Icon
            className={cn(
              'h-4 w-4',
              tone === 'success' && 'text-success',
              tone === 'destructive' && 'text-destructive',
              !tone && 'text-muted-foreground',
            )}
            aria-hidden
          />
        </div>
        {isLoading || value === undefined ? (
          <Skeleton className="mt-2 h-8 w-20" />
        ) : (
          <p className="mt-2 text-2xl font-semibold tabular">{value}</p>
        )}
        {hint ? <p className="mt-1 text-xs text-muted-foreground">{hint}</p> : null}
      </CardContent>
    </Card>
  );
}

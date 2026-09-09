import { PackageSearch, ShoppingBag } from 'lucide-react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { EmptyState, ErrorState } from '@/components/common/states';
import { OrderStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useQueryParams } from '@/hooks/use-query-params';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { ORDER_STATUSES } from '@/utils/constants';
import { formatDateTime, formatMoney } from '@/utils/format';

import { useOrders } from '../hooks';
import type { OrderStatus } from '../types';

const DEFAULTS = {
  page: 0,
  size: 10,
  status: '',
  sortBy: 'createdAt',
  direction: 'desc' as 'asc' | 'desc',
};

const ALL_STATUSES = '__all__';

export default function MyOrdersPage() {
  const { params, setParams } = useQueryParams(DEFAULTS);

  const query = useOrders({
    page: params.page,
    size: params.size,
    status: (params.status || undefined) as OrderStatus | undefined,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const orders = query.data?.content;

  return (
    <div>
      <PageHeader
        title="My orders"
        description="Every order you have placed, newest first."
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'My orders' }]}
        actions={
          <Select
            value={params.status || ALL_STATUSES}
            onValueChange={(status) =>
              setParams({ status: status === ALL_STATUSES ? '' : status, page: 0 })
            }
          >
            <SelectTrigger className="w-48" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_STATUSES}>All statuses</SelectItem>
              {ORDER_STATUSES.map((status) => (
                <SelectItem key={status} value={status}>
                  {status.replace(/_/g, ' ').toLowerCase()}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />

      {query.isError ? (
        <ErrorState error={normalizeError(query.error)} onRetry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 4 }, (_, index) => (
            <Skeleton key={index} className="h-28 rounded-lg" />
          ))}
        </div>
      ) : !orders || orders.length === 0 ? (
        <EmptyState
          icon={params.status ? <PackageSearch className="h-10 w-10" /> : <ShoppingBag className="h-10 w-10" />}
          title={params.status ? 'No orders with that status' : 'You have not ordered yet'}
          description={
            params.status
              ? 'Try clearing the filter to see everything.'
              : 'When you place an order it will appear here, with live progress.'
          }
          action={
            params.status ? (
              <Button variant="outline" onClick={() => setParams({ status: '', page: 0 })}>
                Clear filter
              </Button>
            ) : (
              <Button asChild>
                <Link to={paths.products}>Start shopping</Link>
              </Button>
            )
          }
        />
      ) : (
        <div className="space-y-3">
          {orders.map((order) => (
            <Link key={order.id} to={paths.order(order.id)} className="block">
              <Card className="transition-colors hover:border-primary/50 hover:bg-accent/30">
                <CardContent className="flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-mono text-sm font-medium">{order.orderNumber}</span>
                      <OrderStatusBadge status={order.status} />
                    </div>

                    <p className="mt-1 text-sm text-muted-foreground">
                      {formatDateTime(order.createdAt)} · {order.itemCount}{' '}
                      {order.itemCount === 1 ? 'item' : 'items'}
                    </p>

                    <p className="mt-2 line-clamp-1 text-sm text-muted-foreground">
                      {order.items.map((item) => item.productName).join(', ')}
                    </p>

                    {order.status === 'CANCELLED' && order.failureReason ? (
                      <p className="mt-2 text-xs text-destructive">
                        Stopped at {order.failedStep?.toLowerCase()} —{' '}
                        {order.failureReason.replace(/_/g, ' ').toLowerCase()}
                      </p>
                    ) : null}
                  </div>

                  <p className="shrink-0 text-lg font-semibold tabular">
                    {formatMoney(order.totalAmount, order.currency)}
                  </p>
                </CardContent>
              </Card>
            </Link>
          ))}

          <PagePagination
            data={query.data}
            onPageChange={(page) => setParams({ page })}
            className="pt-4"
          />
        </div>
      )}
    </div>
  );
}

import { useCallback } from 'react';
import { useNavigate } from 'react-router-dom';

import { DataTable, type Column } from '@/components/common/data-table';
import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { OrderStatusBadge } from '@/components/common/status-badge';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useOrders } from '@/features/order/hooks';
import type { Order, OrderStatus } from '@/features/order/types';
import { useQueryParams } from '@/hooks/use-query-params';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { ADMIN_PAGE_SIZE } from '@/shared/config';
import { ORDER_STATUSES } from '@/utils/constants';
import { formatDateTime, formatMoney, humanize } from '@/utils/format';

const DEFAULTS = {
  page: 0,
  size: ADMIN_PAGE_SIZE,
  status: '',
  sortBy: 'createdAt',
  direction: 'desc' as 'asc' | 'desc',
};

const ALL_STATUSES = '__all__';

/**
 * Every order across every customer.
 *
 * The same `GET /api/orders` the customer list uses — the backend widens it automatically for an
 * ADMIN token and accepts a `userId` filter. No separate admin endpoint exists, and none is
 * needed.
 */
export default function AdminOrdersPage() {
  const navigate = useNavigate();
  const { params, setParams } = useQueryParams(DEFAULTS);

  const query = useOrders({
    page: params.page,
    size: params.size,
    status: (params.status || undefined) as OrderStatus | undefined,
    sortBy: params.sortBy,
    direction: params.direction,
  });

  const handleStatusChange = useCallback(
    (status: string) =>
      setParams({ status: status === ALL_STATUSES ? '' : status, page: 0 }),
    [setParams],
  );

  const columns: Column<Order>[] = [
    {
      id: 'order',
      header: 'Order',
      cell: (order) => (
        <div className="min-w-0">
          <p className="font-mono text-sm font-medium">{order.orderNumber}</p>
          <p className="truncate text-xs text-muted-foreground">{order.userEmail}</p>
        </div>
      ),
    },
    {
      id: 'status',
      header: 'Status',
      cell: (order) => (
        <div className="space-y-1">
          <OrderStatusBadge status={order.status} showIcon={false} />
          {order.status === 'CANCELLED' && order.failedStep ? (
            <p className="text-xs text-muted-foreground">
              at {order.failedStep.toLowerCase()}
              {order.failureReason ? ` · ${humanize(order.failureReason)}` : ''}
            </p>
          ) : null}
        </div>
      ),
    },
    {
      id: 'items',
      header: 'Items',
      className: 'text-right',
      hideOnMobile: true,
      cell: (order) => <span className="tabular">{order.itemCount}</span>,
    },
    {
      id: 'total',
      header: 'Total',
      className: 'text-right',
      cell: (order) => (
        <span className="font-medium tabular">
          {formatMoney(order.totalAmount, order.currency)}
        </span>
      ),
    },
    {
      id: 'placed',
      header: 'Placed',
      hideOnMobile: true,
      cell: (order) => (
        <span className="text-sm text-muted-foreground">{formatDateTime(order.createdAt)}</span>
      ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Orders"
        description="Every order across all customers."
        breadcrumbs={[{ label: 'Admin' }, { label: 'Orders' }]}
        actions={
          <Select value={params.status || ALL_STATUSES} onValueChange={handleStatusChange}>
            <SelectTrigger className="w-52" aria-label="Filter by status">
              <SelectValue placeholder="All statuses" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_STATUSES}>All statuses</SelectItem>
              {ORDER_STATUSES.map((status) => (
                <SelectItem key={status} value={status}>
                  {humanize(status)}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />

      <div className="space-y-4">
        <DataTable
          columns={columns}
          rows={query.data?.content}
          getRowId={(order) => order.id}
          isLoading={query.isLoading}
          error={query.error ? normalizeError(query.error) : null}
          onRetry={() => void query.refetch()}
          onRowClick={(order) => navigate(paths.admin.order(order.id))}
          skeletonRows={10}
          emptyTitle={params.status ? 'No orders with that status' : 'No orders yet'}
          emptyDescription={
            params.status
              ? 'Clear the filter to see everything.'
              : 'Orders appear here as customers place them.'
          }
        />

        <PagePagination data={query.data} onPageChange={(page) => setParams({ page })} />
      </div>
    </div>
  );
}

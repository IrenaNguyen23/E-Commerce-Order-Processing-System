import { useQueries } from '@tanstack/react-query';
import { useMemo } from 'react';

import { orderApi } from '@/features/order/api';
import { orderKeys } from '@/features/order/hooks';
import type { Order, OrderStatus } from '@/features/order/types';
import { ORDER_STATUSES } from '@/utils/constants';

export interface StatusBreakdown {
  status: OrderStatus;
  count: number;
  share: number;
}

export interface AdminMetrics {
  totalOrders: number;
  completedOrders: number;
  cancelledOrders: number;
  inFlightOrders: number;
  /** Sum of `totalAmount` over COMPLETED orders only — a cancelled order earned nothing. */
  revenue: number;
  currency: string;
  averageOrderValue: number;
  /** Completed ÷ (completed + cancelled). Undefined until at least one order is terminal. */
  conversionRate: number | undefined;
  breakdown: StatusBreakdown[];
  recentOrders: Order[];
}

/**
 * Dashboard metrics, derived on the client.
 *
 * There is no analytics endpoint — Order Service exposes a paged list and nothing else. So the
 * numbers are computed from what that list reports: one cheap `size=1` query per status gives an
 * exact `totalElements` count without pulling any rows, and a single page of recent orders
 * supplies revenue and the activity feed.
 *
 * The honest limitation, stated where it is used: revenue is calculated over the most recent page
 * of completed orders, not the whole history. For a real reporting surface the backend needs an
 * aggregate endpoint — until then this is accurate about *what it measures* rather than
 * pretending to be a full ledger.
 */
export function useAdminMetrics(revenueSampleSize = 100) {
  const countQueries = useQueries({
    queries: ORDER_STATUSES.map((status) => ({
      queryKey: orderKeys.list({ page: 0, size: 1, status }),
      // `size: 1` because only `totalElements` is wanted — the row itself is discarded.
      queryFn: () => orderApi.search({ page: 0, size: 1, status }),
      staleTime: 30_000,
    })),
  });

  const recentQuery = useQueries({
    queries: [
      {
        queryKey: orderKeys.list({ page: 0, size: revenueSampleSize, sortBy: 'createdAt', direction: 'desc' as const }),
        queryFn: () =>
          orderApi.search({
            page: 0,
            size: revenueSampleSize,
            sortBy: 'createdAt',
            direction: 'desc',
          }),
        staleTime: 30_000,
      },
    ],
  })[0]!;

  const isLoading = countQueries.some((query) => query.isLoading) || recentQuery.isLoading;
  const error = countQueries.find((query) => query.error)?.error ?? recentQuery.error;

  const metrics = useMemo<AdminMetrics | null>(() => {
    if (isLoading || error) return null;

    const counts = new Map<OrderStatus, number>();
    ORDER_STATUSES.forEach((status, index) => {
      counts.set(status, countQueries[index]?.data?.totalElements ?? 0);
    });

    const totalOrders = Array.from(counts.values()).reduce((sum, count) => sum + count, 0);
    const completedOrders = counts.get('COMPLETED') ?? 0;
    const cancelledOrders = counts.get('CANCELLED') ?? 0;
    const inFlightOrders = totalOrders - completedOrders - cancelledOrders;

    const recent = recentQuery.data?.content ?? [];
    const completedRecent = recent.filter((order) => order.status === 'COMPLETED');
    const revenue = completedRecent.reduce((sum, order) => sum + order.totalAmount, 0);

    const terminal = completedOrders + cancelledOrders;

    return {
      totalOrders,
      completedOrders,
      cancelledOrders,
      inFlightOrders,
      revenue,
      currency: recent[0]?.currency ?? 'EUR',
      averageOrderValue: completedRecent.length > 0 ? revenue / completedRecent.length : 0,
      conversionRate: terminal > 0 ? completedOrders / terminal : undefined,
      breakdown: ORDER_STATUSES.map((status) => ({
        status,
        count: counts.get(status) ?? 0,
        share: totalOrders > 0 ? (counts.get(status) ?? 0) / totalOrders : 0,
      })),
      recentOrders: recent.slice(0, 8),
    };
  }, [countQueries, recentQuery.data, isLoading, error]);

  return {
    metrics,
    isLoading,
    error,
    /** How many orders the revenue figure was computed over — surfaced, never hidden. */
    revenueSampleCount: recentQuery.data?.content.filter((o) => o.status === 'COMPLETED').length ?? 0,
    refetch: () => {
      countQueries.forEach((query) => void query.refetch());
      void recentQuery.refetch();
    },
  };
}

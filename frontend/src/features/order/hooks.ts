import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';

import { useCartStore } from '@/features/cart/store';
import { productKeys } from '@/features/product/hooks';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { ORDER_POLL_INTERVAL_MS, ORDER_POLL_TIMEOUT_MS } from '@/shared/config';

import { orderApi } from './api';
import { isInFlight, type CreateOrderPayload, type Order, type OrderSearchParams } from './types';

export const orderKeys = {
  all: ['orders'] as const,
  lists: () => [...orderKeys.all, 'list'] as const,
  list: (params: OrderSearchParams) => [...orderKeys.lists(), params] as const,
  details: () => [...orderKeys.all, 'detail'] as const,
  detail: (id: string) => [...orderKeys.details(), id] as const,
  byNumber: (orderNumber: string) => [...orderKeys.all, 'number', orderNumber] as const,
};

export function useOrders(params: OrderSearchParams) {
  return useQuery({
    queryKey: orderKeys.list(params),
    queryFn: () => orderApi.search(params),
    placeholderData: (previous) => previous,
  });
}

export function useOrder(id: string | undefined) {
  return useQuery({
    queryKey: orderKeys.detail(id ?? ''),
    queryFn: () => orderApi.getById(id!),
    enabled: Boolean(id),
  });
}

/**
 * Watches an order until its saga finishes.
 *
 * `POST /api/orders` returns `CREATED` immediately; inventory, payment and notification resolve
 * over Kafka in the seconds that follow. Polling is what turns that into a live progress display
 * instead of a spinner and a guess.
 *
 * Two rules keep it honest:
 *  - **stop at a terminal state** — `refetchInterval` returns `false` once the order is
 *    `COMPLETED` or `CANCELLED`, so a finished order costs nothing;
 *  - **stop at a deadline** — if a saga stalls (a downstream consumer is down), give up after
 *    90 s and tell the user to check back, rather than polling their battery flat forever.
 */
export function useOrderPolling(id: string | undefined) {
  const [timedOut, setTimedOut] = useState(false);
  const startedAt = useRef<number>(Date.now());

  // Reset the deadline whenever we start watching a different order.
  useEffect(() => {
    startedAt.current = Date.now();
    setTimedOut(false);
  }, [id]);

  const query = useQuery({
    queryKey: orderKeys.detail(id ?? ''),
    queryFn: () => orderApi.getById(id!),
    enabled: Boolean(id),
    refetchInterval: (queryInstance) => {
      const order = queryInstance.state.data;
      if (!order || !isInFlight(order.status)) return false;
      if (Date.now() - startedAt.current > ORDER_POLL_TIMEOUT_MS) return false;
      return ORDER_POLL_INTERVAL_MS;
    },
    // Keep polling when the tab is backgrounded: a customer who switches away mid-checkout
    // should come back to a resolved order, not a stale one.
    refetchIntervalInBackground: true,
  });

  const status = query.data?.status;

  useEffect(() => {
    if (!status || !isInFlight(status)) return;
    const elapsed = Date.now() - startedAt.current;
    const remaining = ORDER_POLL_TIMEOUT_MS - elapsed;
    if (remaining <= 0) {
      setTimedOut(true);
      return;
    }
    const timer = setTimeout(() => setTimedOut(true), remaining);
    return () => clearTimeout(timer);
  }, [status]);

  return {
    ...query,
    /** The saga is still running and we are still watching it. */
    isSagaRunning: Boolean(status && isInFlight(status) && !timedOut),
    /** The saga did not finish within the window — surface a "check back" message, not a spinner. */
    hasTimedOut: timedOut && Boolean(status && isInFlight(status)),
  };
}

/**
 * Places an order.
 *
 * Clears the cart only after the server has confirmed — losing a basket to a failed request is
 * a far worse outcome than a duplicate render.
 */
/**
 * Cancels an order.
 *
 * The order comes back already CANCELLED — that much is knowable immediately. The refund is not:
 * it is the *payment's* status and arrives over a webhook, so the screen keeps polling rather
 * than announcing money is back before anyone has sent it.
 */
export function useCancelOrder() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => orderApi.cancel(id, reason),
    onSuccess: (order) => {
      queryClient.setQueryData(orderKeys.detail(order.id), order);
      void queryClient.invalidateQueries({ queryKey: orderKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: ['payments'] });
      toast.success(`Order ${order.orderNumber} cancelled`);
    },
    // The refusals are the useful part — "still being processed", "already cancelled" — and the
    // server's wording says more than anything generic would.
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function usePlaceOrder() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const clearCart = useCartStore((state) => state.clear);

  return useMutation({
    mutationFn: (payload: CreateOrderPayload) => orderApi.create(payload),

    onSuccess: (order: Order) => {
      clearCart();

      // Seed the detail cache so the success screen renders instantly, then starts polling
      // from a real order rather than from a loading state.
      queryClient.setQueryData(orderKeys.detail(order.id), order);
      void queryClient.invalidateQueries({ queryKey: orderKeys.lists() });
      // Stock has moved: the catalogue the customer just came from is now stale.
      void queryClient.invalidateQueries({ queryKey: productKeys.lists() });

      navigate(paths.orderSuccess(order.id), { replace: true });
    },

    onError: (error) => {
      const appError = normalizeError(error);
      toast.error(appError.message, {
        description: appError.correlationId
          ? `Reference: ${appError.correlationId}`
          : undefined,
      });
    },
  });
}

/**
 * A stable idempotency key for one checkout attempt.
 *
 * Generated when the checkout screen mounts and kept for its lifetime, so a customer who
 * double-clicks Place Order — or retries after a network blip — gets the original order back
 * instead of a second one.
 */
export function useIdempotencyKey(): string {
  const [key] = useState(() => crypto.randomUUID());
  return key;
}

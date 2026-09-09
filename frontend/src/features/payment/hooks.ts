import { useQuery } from '@tanstack/react-query';

import { ORDER_POLL_INTERVAL_MS } from '@/shared/config';

import { paymentApi } from './api';

export const paymentKeys = {
  all: ['payments'] as const,
  detail: (id: string) => [...paymentKeys.all, 'detail', id] as const,
  byOrder: (orderId: string) => [...paymentKeys.all, 'order', orderId] as const,
};

export function usePayment(id: string | undefined) {
  return useQuery({
    queryKey: paymentKeys.detail(id ?? ''),
    queryFn: () => paymentApi.getById(id!),
    enabled: Boolean(id),
  });
}

/**
 * The payment for an order.
 *
 * A 404 is expected, not exceptional: no payment row exists until the saga reaches the payment
 * step. So the query does not retry on it — retrying a legitimate "not yet" three times just
 * delays the UI.
 */
export function usePaymentByOrder(orderId: string | undefined, enabled = true, poll = false) {
  return useQuery({
    queryKey: paymentKeys.byOrder(orderId ?? ''),
    queryFn: () => paymentApi.getByOrderId(orderId!),
    enabled: enabled && Boolean(orderId),
    retry: false,
    // Polled only while the saga is mid-flight. The payment row appears part-way through, and
    // with a real acquirer it arrives carrying the secret the customer needs to pay — so this is
    // how the payment form shows up without the customer reloading the page.
    refetchInterval: poll ? ORDER_POLL_INTERVAL_MS : false,
  });
}

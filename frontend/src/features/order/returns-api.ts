import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { orderKeys } from '@/features/order/hooks';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';
import type { PageResponse } from '@/types/api';

/**
 * Returns.
 *
 * <h2>A return is not an order status</h2>
 *
 * The order stays `COMPLETED` throughout. A return is a separate piece of business that refers to
 * it — the same arrangement as a shipment — so an order can have several, weeks apart, and each
 * carries its own state.
 *
 * <h2>Money follows the goods</h2>
 *
 * Approving a return does not refund it. The sequence is deliberate: approve, the customer sends
 * it back, the warehouse records that it arrived, and only then does anybody press refund. Every
 * step is a separate action by a person, because refunding before the goods are back means paying
 * for something that may never turn up.
 */

export type ReturnStatus =
  | 'REQUESTED'
  | 'APPROVED'
  | 'REJECTED'
  | 'RECEIVED'
  | 'REFUND_PENDING'
  | 'REFUNDED'
  | 'CANCELLED';

export const RETURN_STATUS_LABELS: Record<ReturnStatus, string> = {
  REQUESTED: 'Waiting for a decision',
  APPROVED: 'Approved — send it back',
  REJECTED: 'Refused',
  RECEIVED: 'Goods received',
  REFUND_PENDING: 'Refund in progress',
  REFUNDED: 'Refunded',
  CANCELLED: 'Cancelled',
};

/** The queue an operator works, in the order the work actually happens. */
export const RETURN_QUEUE_STATUSES: ReturnStatus[] = [
  'REQUESTED',
  'APPROVED',
  'RECEIVED',
  'REFUND_PENDING',
  'REFUNDED',
  'REJECTED',
  'CANCELLED',
];

export interface ReturnItem {
  orderItemId: string;
  productId: string;
  productName: string;
  sku: string;
  quantity: number;
  refundAmount: number;
}

export interface ReturnRequest {
  id: string;
  orderId: string;
  orderNumber: string;
  status: ReturnStatus;
  reason: string;
  refundAmount: number;
  currency: string;
  /** True only when the whole order came back — a kept item means a delivered parcel. */
  refundShipping: boolean;
  items: ReturnItem[];
  requestedAt: string;
  decidedAt: string | null;
  decisionNote: string | null;
  receivedAt: string | null;
  /** Whether the goods went back on sale. Null until they arrive. */
  restocked: boolean | null;
  refundedAt: string | null;
  refundReference: string | null;
  /** Why the last refund attempt failed. The return is back in RECEIVED and can be retried. */
  refundFailure: string | null;
}

/** What of an order may still be sent back. */
export interface ReturnableLine {
  orderItemId: string;
  productId: string;
  productName: string;
  sku: string;
  orderedQuantity: number;
  alreadyReturned: number;
  returnableQuantity: number;
  refundPerUnit: number;
  currency: string;
}

export interface CreateReturnPayload {
  items: Array<{ orderItemId: string; quantity: number }>;
  reason: string;
}

export const returnKeys = {
  all: ['returns'] as const,
  forOrder: (orderId: string) => [...returnKeys.all, 'order', orderId] as const,
  returnable: (orderId: string) => [...returnKeys.all, 'returnable', orderId] as const,
  mine: () => [...returnKeys.all, 'mine'] as const,
  queue: (status: ReturnStatus | 'ALL', page: number) =>
    [...returnKeys.all, 'queue', status, page] as const,
};

export const returnApi = {
  returnable: (orderId: string): Promise<ReturnableLine[]> =>
    api.get<ReturnableLine[]>(endpoints.returns.returnable(orderId)),

  forOrder: (orderId: string): Promise<ReturnRequest[]> =>
    api.get<ReturnRequest[]>(endpoints.returns.forOrder(orderId)),

  mine: (): Promise<ReturnRequest[]> => api.get<ReturnRequest[]>(endpoints.returns.mine),

  create: (orderId: string, payload: CreateReturnPayload): Promise<ReturnRequest> =>
    api.post<ReturnRequest>(endpoints.returns.forOrder(orderId), payload),

  cancel: (returnId: string): Promise<ReturnRequest> =>
    api.post<ReturnRequest>(endpoints.returns.cancel(returnId)),

  queue: (status: ReturnStatus | 'ALL', page: number, size = 20):
    Promise<PageResponse<ReturnRequest>> =>
    api.get<PageResponse<ReturnRequest>>(endpoints.returns.queue, {
      params: { status: status === 'ALL' ? undefined : status, page, size },
    }),

  approve: (returnId: string, note?: string): Promise<ReturnRequest> =>
    api.post<ReturnRequest>(endpoints.returns.approve(returnId), { note }),

  reject: (returnId: string, note?: string): Promise<ReturnRequest> =>
    api.post<ReturnRequest>(endpoints.returns.reject(returnId), { note }),

  received: (returnId: string, restock: boolean): Promise<ReturnRequest> =>
    api.post<ReturnRequest>(endpoints.returns.received(returnId), { restock }),

  refund: (returnId: string): Promise<ReturnRequest> =>
    api.post<ReturnRequest>(endpoints.returns.refund(returnId)),
};

// ---------------------------------------------------------------------------------------------
// Reads
// ---------------------------------------------------------------------------------------------

/**
 * What is still returnable on an order.
 *
 * <p>Fetched before the form opens, so it can never offer a quantity the backend would refuse.
 * Asking somebody to guess and then rejecting them is a worse experience than showing the answer.
 */
export function useReturnableLines(orderId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: returnKeys.returnable(orderId ?? ''),
    queryFn: () => returnApi.returnable(orderId!),
    enabled: Boolean(orderId) && enabled,
  });
}

export function useOrderReturns(orderId: string | undefined) {
  return useQuery({
    queryKey: returnKeys.forOrder(orderId ?? ''),
    queryFn: () => returnApi.forOrder(orderId!),
    enabled: Boolean(orderId),
  });
}

export function useReturnQueue(status: ReturnStatus | 'ALL', page: number) {
  return useQuery({
    queryKey: returnKeys.queue(status, page),
    queryFn: () => returnApi.queue(status, page),
    // Two people working the same queue should not both approve the same return.
    staleTime: 10_000,
  });
}

// ---------------------------------------------------------------------------------------------
// Writes
// ---------------------------------------------------------------------------------------------

function useReturnMutation<TVariables>(
  mutationFn: (variables: TVariables) => Promise<ReturnRequest>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: returnKeys.all });
      // The order page lists its returns, and what is still returnable has changed.
      void queryClient.invalidateQueries({ queryKey: orderKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useCreateReturn() {
  return useReturnMutation(
    ({ orderId, payload }: { orderId: string; payload: CreateReturnPayload }) =>
      returnApi.create(orderId, payload),
    'We have your return request',
  );
}

export function useCancelReturn() {
  return useReturnMutation((returnId: string) => returnApi.cancel(returnId), 'Return cancelled');
}

export function useApproveReturn() {
  return useReturnMutation(
    ({ returnId, note }: { returnId: string; note?: string }) =>
      returnApi.approve(returnId, note),
    'Return approved',
  );
}

export function useRejectReturn() {
  return useReturnMutation(
    ({ returnId, note }: { returnId: string; note?: string }) => returnApi.reject(returnId, note),
    'Return refused',
  );
}

/**
 * Receiving goods, with the decision that has to come with them.
 *
 * <p>`restock` has no default here either. Not everything that comes back can be sold again, and
 * the person holding the box is the only one who can tell — guessing true sells the next customer
 * something broken, guessing false quietly writes off stock that was fine.
 */
export function useMarkReturnReceived() {
  return useReturnMutation(
    ({ returnId, restock }: { returnId: string; restock: boolean }) =>
      returnApi.received(returnId, restock),
    'Goods received',
  );
}

export function useRefundReturn() {
  return useReturnMutation(
    (returnId: string) => returnApi.refund(returnId),
    'Refund requested — it will show as refunded once the provider confirms',
  );
}

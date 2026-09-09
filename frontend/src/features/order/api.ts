import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import type { PageResponse } from '@/types/api';

import type { CreateOrderPayload, Order, OrderSearchParams } from './types';

export const orderApi = {
  /**
   * Places an order and starts the saga.
   *
   * `skipRetry` is not a detail — it is the single most important flag in the whole client. The
   * transient-failure retry replays a request after a timeout, and a timeout does not mean the
   * request failed: it means the answer never arrived. Replaying this one could place a second
   * order and charge the customer twice.
   *
   * The `idempotencyKey` on the payload is the backend's own defence against the same problem,
   * so a retry the *customer* initiates is safe. An automatic one still is not, because it would
   * hide the ambiguity instead of surfacing it.
   */
  create: (payload: CreateOrderPayload): Promise<Order> =>
    api.post<Order>(endpoints.orders.create, payload, { skipRetry: true }),

  /**
   * Cancels an order and unwinds whatever it had already done.
   *
   * `skipRetry` for the same reason as placing one: this moves money. An automatic replay after a
   * timeout would ask for a second cancellation of an order that may already be refunding, and
   * the ambiguity belongs in front of the customer rather than hidden by a retry.
   */
  cancel: (id: string, reason?: string): Promise<Order> =>
    api.post<Order>(endpoints.orders.cancel(id), { reason }, { skipRetry: true }),

  search: (params: OrderSearchParams = {}): Promise<PageResponse<Order>> =>
    api.get<PageResponse<Order>>(endpoints.orders.list, { params }),

  getById: (id: string): Promise<Order> => api.get<Order>(endpoints.orders.byId(id)),

  getByNumber: (orderNumber: string): Promise<Order> =>
    api.get<Order>(endpoints.orders.byNumber(orderNumber)),
};

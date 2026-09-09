import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';

import type { Payment, ProcessPaymentPayload } from './types';

export const paymentApi = {
  /**
   * Manual charge. The normal flow charges automatically on `inventory.reserved`; this is the
   * operator-driven retry path, and it enforces the same one-payment-per-order guarantee.
   *
   * A decline comes back as `402` **with the payment as the body** — the charge was recorded and
   * the saga was told, the status code only reports that the order is not paid.
   */
  process: (payload: ProcessPaymentPayload): Promise<Payment> =>
    api.post<Payment>(endpoints.payments.process, payload, { skipRetry: true }),

  getById: (id: string): Promise<Payment> => api.get<Payment>(endpoints.payments.byId(id)),

  getByOrderId: (orderId: string): Promise<Payment> =>
    api.get<Payment>(endpoints.payments.byOrderId(orderId)),
};

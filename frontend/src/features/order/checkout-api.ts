import { useMutation, useQuery } from '@tanstack/react-query';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

import type { CouponPreview, ShippingQuote } from './types';

/**
 * The two things a checkout page has to ask about before anything is committed: what delivery
 * costs, and what a discount code is worth.
 *
 * Both are quotes. Neither reserves anything, neither spends anything, and neither decides what
 * the customer is charged — the order is priced again on the server when it is placed, from the
 * same rate card and the same coupon table. That second calculation is the one that counts.
 *
 * The consequence is worth stating plainly: a code can pass the preview and be refused at
 * checkout, if the last one went in between. That is what a limited campaign means, and the
 * checkout says so rather than quietly placing the order at full price.
 */

export interface ShippingQuoteParams {
  countryCode: string;
  itemCount: number;
  /** What the customer is paying for the goods, so a free-delivery threshold is measured right. */
  goodsAfterDiscount: number;
  currency: string;
}

export const checkoutKeys = {
  shipping: (params: ShippingQuoteParams) => ['shipping-quotes', params] as const,
};

export const checkoutApi = {
  shippingQuotes: (params: ShippingQuoteParams): Promise<ShippingQuote[]> =>
    api.get<ShippingQuote[]>(endpoints.shipping.quotes, { params }),

  previewCoupon: (payload: {
    code: string;
    basketAmount: number;
    shippingAmount: number;
    currency: string;
  }): Promise<CouponPreview> => api.post<CouponPreview>(endpoints.coupons.preview, payload),
};

/**
 * Delivery options for a destination.
 *
 * Disabled until there is a country to quote against. Asking for options for nowhere would either
 * fail or, worse, return the rest-of-world rate and show it as though it applied.
 */
export function useShippingQuotes(params: Partial<ShippingQuoteParams>) {
  const ready = Boolean(params.countryCode && params.countryCode.length === 2);

  return useQuery({
    queryKey: checkoutKeys.shipping(params as ShippingQuoteParams),
    queryFn: () => checkoutApi.shippingQuotes(params as ShippingQuoteParams),
    enabled: ready,
    // Rate cards are edited by an operator, not by traffic. Refetching them on every keystroke
    // in the address form would be a request per character for a table that changes monthly.
    staleTime: 5 * 60 * 1000,
  });
}

/**
 * Tries a code without spending it.
 *
 * A mutation rather than a query even though it changes nothing on the server, because it is a
 * deliberate act by the customer — pressing Apply — and should not be retried, cached or refetched
 * behind their back.
 */
export function usePreviewCoupon() {
  return useMutation({
    mutationFn: checkoutApi.previewCoupon,
    // No toast here. The checkout page shows the refusal next to the field the customer typed
    // into, which is where they are looking, and the message says which reason it was.
    onError: (error) => normalizeError(error),
  });
}

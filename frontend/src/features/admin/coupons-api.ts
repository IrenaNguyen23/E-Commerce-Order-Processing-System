import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';
import type { PageResponse } from '@/types/api';

/**
 * Discount codes, from the back office.
 *
 * <h2>`value` is a fraction for a percentage, and the API does not guess</h2>
 *
 * `0.10` is ten per cent. Sending `10` is refused rather than interpreted, because accepting it
 * and guessing whether it meant ten per cent or ten euros is the kind of convenience that
 * eventually creates a code worth ten times what somebody intended.
 *
 * The form converts visibly: an operator types 10 into a field labelled "%" and this module sends
 * 0.1. The conversion lives at the edge, once, rather than being spread across every caller.
 */

export type DiscountType = 'PERCENTAGE' | 'FIXED_AMOUNT' | 'FREE_SHIPPING';

export const DISCOUNT_TYPE_LABELS: Record<DiscountType, string> = {
  PERCENTAGE: 'Percentage off',
  FIXED_AMOUNT: 'Amount off',
  FREE_SHIPPING: 'Free delivery',
};

export interface Coupon {
  id: string;
  code: string;
  description: string | null;
  type: DiscountType;
  /** A fraction for a percentage; an amount for a fixed sum. */
  value: number;
  currency: string | null;
  minimumBasket: number | null;
  maxRedemptions: number | null;
  redemptionCount: number;
  perCustomerLimit: number | null;
  validFrom: string | null;
  validUntil: string | null;
  /** The operator's switch. */
  active: boolean;
  /**
   * Whether it would be accepted right now, ignoring any basket.
   *
   * Computed server-side rather than stored: a campaign expires by the clock, not by a sweep, so a
   * stored flag would be right only as often as something remembered to run.
   */
  live: boolean;
  remaining: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface CouponPayload {
  code: string;
  description?: string;
  type: DiscountType;
  value: number;
  currency?: string;
  minimumBasket?: number;
  maxRedemptions?: number;
  perCustomerLimit?: number;
  validFrom?: string;
  validUntil?: string;
  active?: boolean;
}

export interface Redemption {
  orderId: string;
  userId: string;
  /** What it was worth on that order — which can be less than the code's face value. */
  discountAmount: number;
  currency: string;
  redeemedAt: string;
}

export const couponKeys = {
  all: ['coupons'] as const,
  list: (active: boolean | undefined, page: number) =>
    [...couponKeys.all, 'list', active, page] as const,
  redemptions: (id: string) => [...couponKeys.all, 'redemptions', id] as const,
};

export const couponApi = {
  list: (active: boolean | undefined, page = 0, size = 20): Promise<PageResponse<Coupon>> =>
    api.get<PageResponse<Coupon>>(endpoints.coupons.list, { params: { active, page, size } }),

  create: (payload: CouponPayload): Promise<Coupon> =>
    api.post<Coupon>(endpoints.coupons.list, payload),

  update: (id: string, payload: CouponPayload): Promise<Coupon> =>
    api.put<Coupon>(endpoints.coupons.byId(id), payload),

  remove: (id: string): Promise<void> => api.delete<void>(endpoints.coupons.byId(id)),

  redemptions: (id: string): Promise<Redemption[]> =>
    api.get<Redemption[]>(endpoints.coupons.redemptions(id)),
};

export function useCoupons(active: boolean | undefined, page = 0) {
  return useQuery({
    queryKey: couponKeys.list(active, page),
    queryFn: () => couponApi.list(active, page),
    placeholderData: (previous) => previous,
  });
}

export function useCouponRedemptions(couponId: string | undefined) {
  return useQuery({
    queryKey: couponKeys.redemptions(couponId ?? ''),
    queryFn: () => couponApi.redemptions(couponId!),
    enabled: Boolean(couponId),
  });
}

function useCouponMutation<TVariables, TData>(
  mutationFn: (variables: TVariables) => Promise<TData>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: couponKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useCreateCoupon() {
  return useCouponMutation((payload: CouponPayload) => couponApi.create(payload), 'Code created');
}

export function useUpdateCoupon() {
  return useCouponMutation(
    ({ id, payload }: { id: string; payload: CouponPayload }) => couponApi.update(id, payload),
    'Code updated',
  );
}

export function useDeleteCoupon() {
  return useCouponMutation((id: string) => couponApi.remove(id), 'Code deleted');
}

/**
 * What to show next to a code.
 *
 * <p>`active` and `live` are different questions and the UI has to answer both: a code can be
 * switched on and still not work because its window has closed or its allowance has gone. Showing
 * only `active` would leave an operator wondering why a code they can see is on does nothing.
 */
export function couponStatus(coupon: Coupon): { label: string; tone: 'live' | 'off' | 'spent' } {
  if (!coupon.active) {
    return { label: 'Switched off', tone: 'off' };
  }
  if (coupon.remaining === 0) {
    return { label: 'Fully redeemed', tone: 'spent' };
  }
  if (coupon.validUntil && new Date(coupon.validUntil) < new Date()) {
    return { label: 'Expired', tone: 'spent' };
  }
  if (coupon.validFrom && new Date(coupon.validFrom) > new Date()) {
    return { label: 'Not started', tone: 'off' };
  }
  return { label: 'Live', tone: 'live' };
}

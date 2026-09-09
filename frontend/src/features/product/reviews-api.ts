import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { selectIsAuthenticated, useAuthStore } from '@/features/auth/store';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';
import type { PageResponse } from '@/types/api';

import { productKeys } from './hooks';

/** What customers thought. Mirrors `com.commerceflow.inventoryservice.review`. */

export type ReviewStatus = 'PENDING' | 'PUBLISHED' | 'REJECTED';

export interface Review {
  id: string;
  productId: string;
  /** As the author chose to be shown. Never an email address — see the API's own note. */
  authorName: string;
  rating: number;
  title: string | null;
  body: string | null;
  status: ReviewStatus;
  /** True when the author had bought the product when they wrote this. */
  verifiedPurchase: boolean;
  /** Why it was rejected. Shown to its author, and to nobody else. */
  moderationNote: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReviewPayload {
  rating: number;
  title?: string;
  body?: string;
  authorName?: string;
}

export const reviewKeys = {
  all: ['reviews'] as const,
  forProduct: (productId: string) => [...reviewKeys.all, 'product', productId] as const,
  mine: () => [...reviewKeys.all, 'mine'] as const,
  pending: () => [...reviewKeys.all, 'pending'] as const,
};

export const reviewApi = {
  forProduct: (productId: string, page = 0, size = 10): Promise<PageResponse<Review>> =>
    api.get<PageResponse<Review>>(endpoints.reviews.forProduct(productId), {
      params: { page, size },
    }),

  mine: (): Promise<Review[]> => api.get<Review[]>(endpoints.reviews.mine),

  submit: (productId: string, payload: ReviewPayload): Promise<Review> =>
    api.put<Review>(endpoints.reviews.forProduct(productId), payload),

  pending: (page = 0, size = 20): Promise<PageResponse<Review>> =>
    api.get<PageResponse<Review>>(endpoints.reviews.pending, { params: { page, size } }),

  moderate: (reviewId: string, publish: boolean, note?: string): Promise<Review> =>
    api.post<Review>(endpoints.reviews.moderate(reviewId), null, {
      params: { publish, note },
    }),

  remove: (reviewId: string): Promise<void> => api.delete<void>(endpoints.reviews.byId(reviewId)),
};

export function useProductReviews(productId: string | undefined, page = 0) {
  return useQuery({
    queryKey: [...reviewKeys.forProduct(productId ?? ''), page],
    queryFn: () => reviewApi.forProduct(productId!, page),
    enabled: Boolean(productId),
  });
}

/** Everything the caller has written, including anything still waiting or rejected. */
export function useMyReviews() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);

  return useQuery({
    queryKey: reviewKeys.mine(),
    queryFn: reviewApi.mine,
    enabled: isAuthenticated,
  });
}

/**
 * Writes or replaces the caller's review.
 *
 * The success message says the review is waiting rather than that it is published, because by
 * default it is. Telling somebody their review is live when a moderator has not seen it yet is a
 * small lie they discover by refreshing the page.
 */
export function useSubmitReview(productId: string) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: ReviewPayload) => reviewApi.submit(productId, payload),
    onSuccess: (review) => {
      void queryClient.invalidateQueries({ queryKey: reviewKeys.forProduct(productId) });
      void queryClient.invalidateQueries({ queryKey: reviewKeys.mine() });
      // The rating on the product changes when a review is published, so the product itself is
      // stale too.
      void queryClient.invalidateQueries({ queryKey: productKeys.detail(productId) });

      toast.success(
        review.status === 'PUBLISHED'
          ? 'Thanks — your review is live'
          : 'Thanks — your review will appear once it has been checked',
      );
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

/** The moderation queue. */
export function usePendingReviews(page = 0) {
  return useQuery({
    queryKey: [...reviewKeys.pending(), page],
    queryFn: () => reviewApi.pending(page),
  });
}

export function useModerateReview() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ reviewId, publish, note }: {
      reviewId: string; publish: boolean; note?: string;
    }) => reviewApi.moderate(reviewId, publish, note),
    onSuccess: (review, variables) => {
      void queryClient.invalidateQueries({ queryKey: reviewKeys.all });
      void queryClient.invalidateQueries({ queryKey: productKeys.detail(review.productId) });
      toast.success(variables.publish ? 'Review published' : 'Review rejected');
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

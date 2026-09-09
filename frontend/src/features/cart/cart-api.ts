import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { toast } from 'sonner';

import { selectIsAuthenticated, useAuthStore } from '@/features/auth/store';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

import { useCartStore } from './store';

/**
 * The basket, on the server.
 *
 * <h2>Why the local basket did not simply go away</h2>
 *
 * A basket has to work before anybody has signed in, and it has to render instantly. Both of those
 * argue for keeping it in the browser. What the browser cannot do is follow somebody from their
 * phone to their laptop, which is the entire reason for a server basket.
 *
 * So both exist, with one rule that keeps them from disagreeing: **the local basket is the render
 * source, and every change to it is written through to the server when there is somebody to write
 * it for.** Signing in merges what is in the browser into what the account already had, and from
 * then on the two move together.
 *
 * The alternative — a request per keystroke on a quantity control, with the page waiting for it —
 * is a basket that feels broken on a train.
 */

export type CartIssue = string | null;

export interface ServerCartLine {
  productId: string;
  sku: string | null;
  name: string;
  imageUrl: string | null;
  quantity: number;
  unitPrice: number;
  lineTotal: number;
  currency: string | null;
  availableQuantity: number;
  available: boolean;
  /** "Out of stock", "Only 2 left", "No longer for sale". Null when the line is fine. */
  issue: CartIssue;
  addedAt: string | null;
}

export interface ServerCart {
  id: string;
  lines: ServerCartLine[];
  totalQuantity: number;
  total: number;
  currency: string;
  checkoutable: boolean;
  updatedAt: string;
}

export const cartKeys = {
  all: ['server-cart'] as const,
};

export const cartApi = {
  get: (): Promise<ServerCart> => api.get<ServerCart>(endpoints.cart.get),

  put: (productId: string, quantity: number): Promise<ServerCart> =>
    api.put<ServerCart>(endpoints.cart.items, { productId, quantity }),

  remove: (productId: string): Promise<ServerCart> =>
    api.delete<ServerCart>(endpoints.cart.item(productId)),

  clear: (): Promise<ServerCart> => api.delete<ServerCart>(endpoints.cart.get),

  merge: (lines: Array<{ productId: string; quantity: number }>): Promise<ServerCart> =>
    api.post<ServerCart>(endpoints.cart.merge, lines),
};

/** The stored basket. Only asked for when there is somebody to ask on behalf of. */
export function useServerCart() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);

  return useQuery({
    queryKey: cartKeys.all,
    queryFn: cartApi.get,
    enabled: isAuthenticated,
    staleTime: 30 * 1000,
  });
}

/**
 * Keeps the stored basket in step with the local one.
 *
 * Deliberately quiet: it reports nothing on success and does not invalidate anything the page is
 * rendering from. The local basket has already updated the screen; this is the durable copy
 * catching up, and a spinner or a toast for it would be reporting our bookkeeping as if it were
 * the customer's business.
 */
export function useCartWriteThrough() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);

  const mutation = useMutation({
    mutationFn: ({ productId, quantity }: { productId: string; quantity: number }) =>
      cartApi.put(productId, quantity),
    // Failures are logged, not shown. The customer's basket is correct on their screen and will
    // be reconciled at the next sign-in; interrupting them to say a background write failed
    // would be worse than the failure.
    onError: (error) =>
      console.warn('Could not save the basket to your account:', normalizeError(error).message),
  });

  return {
    write: (productId: string, quantity: number) => {
      if (!isAuthenticated) return;
      mutation.mutate({ productId, quantity });
    },
  };
}

/**
 * Folds the browser basket into the account's on sign-in, once.
 *
 * The larger quantity of each product wins rather than the sum — see the API's own note on why.
 * Anything the account had that the browser did not is adopted, so a basket started on a phone is
 * there on the laptop.
 */
export function useCartSync() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const userId = useAuthStore((state) => state.user?.id);
  const queryClient = useQueryClient();

  const localItems = useCartStore((state) => state.items);
  const adopt = useCartStore((state) => state.adoptServerLines);

  // One merge per sign-in. Without this guard every render that touches the basket would
  // re-merge, and a quantity the customer had just reduced would spring back to its old value.
  const mergedFor = useRef<string | null>(null);

  useEffect(() => {
    if (!isAuthenticated || !userId || mergedFor.current === userId) {
      return;
    }
    mergedFor.current = userId;

    const lines = localItems.map((item) => ({
      productId: item.productId,
      quantity: item.quantity,
    }));

    cartApi
      .merge(lines)
      .then((merged) => {
        adopt(merged.lines.map((line) => ({ productId: line.productId, quantity: line.quantity })));
        void queryClient.invalidateQueries({ queryKey: cartKeys.all });

        const adopted = merged.lines.length - lines.length;
        if (adopted > 0) {
          toast.info(
            adopted === 1
              ? 'We added an item you saved on another device'
              : `We added ${adopted} items you saved on another device`,
          );
        }
      })
      .catch((error) => {
        // Not fatal: the browser basket is intact and checkout works from it. Retrying on the
        // next sign-in is better than blocking somebody who only wanted to look at a product.
        mergedFor.current = null;
        console.warn('Could not merge your saved basket:', normalizeError(error).message);
      });
  }, [isAuthenticated, userId, localItems, adopt, queryClient]);
}

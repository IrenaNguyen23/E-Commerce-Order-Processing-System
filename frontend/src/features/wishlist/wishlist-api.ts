import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { selectIsAuthenticated, useAuthStore } from '@/features/auth/store';
import { cartKeys } from '@/features/cart/cart-api';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

import { useWishlistStore } from './store';

/**
 * Saved items, on the server.
 *
 * <h2>Priced live, always</h2>
 *
 * The most useful thing a wishlist does is let somebody notice that a thing they wanted has come
 * down in price. A stored price would hide exactly that, so the server returns today's figure and
 * nothing here caches one.
 *
 * <h2>Signed out, it still works</h2>
 *
 * The local store stays as the anonymous wishlist. Signing in pushes it up once and then the
 * server is the list — the same arrangement as the basket, for the same reason: it has to work
 * before there is an account, and it has to follow somebody between devices afterwards.
 */

export interface WishlistEntry {
  productId: string;
  sku: string | null;
  name: string;
  imageUrl: string | null;
  /** Today's price, not the price when it was saved. */
  price: number | null;
  currency: string | null;
  purchasable: boolean;
  availableQuantity: number;
  addedAt: string;
}

export const wishlistKeys = {
  all: ['wishlist'] as const,
};

export const wishlistApi = {
  list: (): Promise<WishlistEntry[]> => api.get<WishlistEntry[]>(endpoints.wishlist.list),

  add: (productId: string): Promise<void> =>
    api.post<void>(endpoints.wishlist.item(productId), {}),

  remove: (productId: string): Promise<void> =>
    api.delete<void>(endpoints.wishlist.item(productId)),

  moveToCart: (productId: string, quantity = 1): Promise<unknown> =>
    api.post<unknown>(`${endpoints.wishlist.moveToCart(productId)}?quantity=${quantity}`, {}),
};

export function useWishlist() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);

  return useQuery({
    queryKey: wishlistKeys.all,
    queryFn: wishlistApi.list,
    enabled: isAuthenticated,
    staleTime: 60 * 1000,
  });
}

/**
 * Saves or forgets an item, wherever it belongs.
 *
 * Signed in, that is the server. Signed out, the browser. The caller does not have to know which,
 * which is what stops a "save" button on a product card growing an auth check of its own.
 */
export function useToggleWishlist() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const queryClient = useQueryClient();

  const localHas = useWishlistStore((state) => state.has);
  const localAdd = useWishlistStore((state) => state.add);
  const localRemove = useWishlistStore((state) => state.remove);

  const mutation = useMutation({
    mutationFn: ({ productId, saved }: { productId: string; saved: boolean }) =>
      saved ? wishlistApi.remove(productId) : wishlistApi.add(productId),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: wishlistKeys.all }),
    onError: (error) => toast.error(normalizeError(error).message),
  });

  return {
    isPending: mutation.isPending,
    toggle: (product: { id: string; sku: string; name: string; price: number;
      currency: string; imageUrl: string | null }, saved: boolean) => {

      if (!isAuthenticated) {
        if (saved) {
          localRemove(product.id);
        } else {
          localAdd(product as never);
        }
        return;
      }
      mutation.mutate({ productId: product.id, saved });
    },
    isSaved: (productId: string, serverEntries?: WishlistEntry[]) =>
      isAuthenticated
        ? Boolean(serverEntries?.some((entry) => entry.productId === productId))
        : localHas(productId),
  };
}

/**
 * Moves a saved item into the basket.
 *
 * Removed from the wishlist only once the basket has taken it. If the basket refuses — the
 * product is gone, the basket is full — the customer still has it saved, which is exactly the
 * state they were in before they pressed anything.
 */
export function useMoveToCart() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ productId, quantity = 1 }: { productId: string; quantity?: number }) =>
      wishlistApi.moveToCart(productId, quantity),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: wishlistKeys.all });
      void queryClient.invalidateQueries({ queryKey: cartKeys.all });
      toast.success('Moved to your basket');
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

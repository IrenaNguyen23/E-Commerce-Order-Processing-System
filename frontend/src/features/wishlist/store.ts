import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

import type { Product } from '@/features/product/types';
import { storageKeys } from '@/shared/config';

/**
 * The wishlist.
 *
 * Local for the same reason as the cart: it is a user preference with no server state to
 * reconcile, and there is no backend endpoint for it. Only ids and a display snapshot are kept —
 * the live product record is re-fetched with the same batch lookup the cart uses.
 */

export interface WishlistEntry {
  productId: string;
  snapshot: {
    sku: string;
    name: string;
    price: number;
    currency: string;
    imageUrl: string | null;
  };
  addedAt: string;
}

interface WishlistState {
  entries: WishlistEntry[];

  toggle: (product: Product) => boolean;
  add: (product: Product) => void;
  remove: (productId: string) => void;
  clear: () => void;

  has: (productId: string) => boolean;
  count: () => number;
}

export const useWishlistStore = create<WishlistState>()(
  persist(
    (set, get) => ({
      entries: [],

      /** @returns whether the product is on the list *after* the toggle, for the toast wording. */
      toggle: (product) => {
        const exists = get().entries.some((entry) => entry.productId === product.id);
        if (exists) {
          get().remove(product.id);
          return false;
        }
        get().add(product);
        return true;
      },

      add: (product) => {
        if (get().entries.some((entry) => entry.productId === product.id)) return;
        set({
          entries: [
            {
              productId: product.id,
              snapshot: {
                sku: product.sku,
                name: product.name,
                price: product.price,
                currency: product.currency,
                imageUrl: product.imageUrl,
              },
              addedAt: new Date().toISOString(),
            },
            // Newest first: the thing just saved should be at the top of the list.
            ...get().entries,
          ],
        });
      },

      remove: (productId) =>
        set({ entries: get().entries.filter((entry) => entry.productId !== productId) }),

      clear: () => set({ entries: [] }),

      has: (productId) => get().entries.some((entry) => entry.productId === productId),

      count: () => get().entries.length,
    }),
    {
      name: storageKeys.wishlist,
      storage: createJSONStorage(() => localStorage),
      partialize: (state) => ({ entries: state.entries }),
      version: 1,
    },
  ),
);

export const selectWishlistCount = (state: WishlistState) => state.entries.length;

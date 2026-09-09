import { useAuthStore, selectIsAuthenticated } from '@/features/auth/store';
import type { Product } from '@/features/product/types';

import { useToggleWishlist, useWishlist } from './wishlist-api';
import { useWishlistStore } from './store';

/**
 * The wishlist, wherever it lives.
 *
 * <p>Signed in, that is the server; signed out, the browser. Every caller gets the same three
 * things — the entries, whether a product is in them, and a toggle — so a heart button on a
 * product card does not have to grow an auth check of its own.
 *
 * <p>Both halves are real. The local one has to exist because saving something is a reasonable
 * thing to do before creating an account, and the server one has to exist because a list that
 * does not follow you between devices is barely a list.
 */
export interface WishlistItemView {
  productId: string;
  name: string;
  sku: string | null;
  imageUrl: string | null;
  /** Today's price. Null for a product that has since left the catalogue. */
  price: number | null;
  currency: string | null;
  purchasable: boolean;
  addedAt: string;
}

export function useWishlistView() {
  const isAuthenticated = useAuthStore(selectIsAuthenticated);
  const server = useWishlist();
  const local = useWishlistStore((state) => state.entries);
  const localClear = useWishlistStore((state) => state.clear);
  const { toggle, isSaved } = useToggleWishlist();

  const items: WishlistItemView[] = isAuthenticated
    ? (server.data ?? []).map((entry) => ({
        productId: entry.productId,
        name: entry.name,
        sku: entry.sku,
        imageUrl: entry.imageUrl,
        price: entry.price,
        currency: entry.currency,
        purchasable: entry.purchasable,
        addedAt: entry.addedAt,
      }))
    : local.map((entry) => ({
        productId: entry.productId,
        name: entry.snapshot.name,
        sku: entry.snapshot.sku,
        imageUrl: entry.snapshot.imageUrl,
        price: entry.snapshot.price,
        currency: entry.snapshot.currency,
        // A signed-out list has no live stock figure, so it does not claim one. The product
        // page is where somebody finds out whether they can actually buy it.
        purchasable: true,
        addedAt: entry.addedAt,
      }));

  return {
    items,
    isLoading: isAuthenticated && server.isLoading,
    isServerBacked: isAuthenticated,
    has: (productId: string) => isSaved(productId, server.data),
    toggle: (product: Product) => toggle(product, isSaved(product.id, server.data)),
    clear: () => {
      if (!isAuthenticated) {
        localClear();
        return;
      }
      // No bulk delete on the server, and adding one for a button nobody presses twice would be
      // a new endpoint to secure and test. One request per item is fine at wishlist sizes.
      items.forEach((item) => toggle({ id: item.productId } as Product, true));
    },
  };
}

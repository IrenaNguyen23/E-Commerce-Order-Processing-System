import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';

import type { Product } from '@/features/product/types';
import { MAX_ITEM_QUANTITY, MAX_ORDER_ITEMS, storageKeys } from '@/shared/config';

/**
 * The cart in the browser.
 *
 * This is the **render source**: it works before anybody signs in, it works offline, and it
 * updates a quantity control without waiting for a request. What it cannot do is follow somebody
 * from their phone to their laptop, which is what the server basket is for — see `cart-api.ts`.
 *
 * The two are kept in step by one rule: every change here is written through to the server when
 * there is an account to write it to, and signing in merges this basket into that one. Neither is
 * a cache of the other; the browser owns *now* and the server owns *between devices*.
 *
 * What is stored is deliberately minimal: **ids and quantities plus a display snapshot**, never a
 * price the checkout trusts. The snapshot exists so the basket renders instantly; the
 * authoritative price is re-fetched at checkout and the backend prices the order again regardless,
 * so a stale localStorage entry can never affect what a customer is charged.
 */

export interface CartItem {
  productId: string;
  quantity: number;
  /** Display-only snapshot, refreshed whenever the catalogue is queried. Never used to charge. */
  snapshot: {
    sku: string;
    name: string;
    price: number;
    currency: string;
    imageUrl: string | null;
  };
  addedAt: string;
}

interface CartState {
  items: CartItem[];

  add: (product: Product, quantity?: number) => void;
  setQuantity: (productId: string, quantity: number) => void;
  remove: (productId: string) => void;
  clear: () => void;

  /** Refreshes snapshots after a catalogue fetch, and drops products that no longer exist. */
  reconcile: (products: Map<string, Product>) => void;

  /**
   * Takes the quantities the server settled on after a sign-in merge.
   *
   * Quantities only — never prices or names. Those come from the catalogue, and letting a merge
   * response write them would give the basket a second source of display data that ages
   * differently from the first.
   */
  adoptServerLines: (lines: Array<{ productId: string; quantity: number }>) => void;

  getQuantity: (productId: string) => number;
  itemCount: () => number;
  distinctCount: () => number;
  /** Indicative only — the checkout total comes from freshly fetched prices. */
  estimatedTotal: () => number;
}

const clampQuantity = (value: number): number =>
  Math.max(1, Math.min(MAX_ITEM_QUANTITY, Math.floor(value)));

export const useCartStore = create<CartState>()(
  persist(
    (set, get) => ({
      items: [],

      add: (product, quantity = 1) => {
        const items = get().items;
        const existing = items.find((item) => item.productId === product.id);

        if (existing) {
          set({
            items: items.map((item) =>
              item.productId === product.id
                ? { ...item, quantity: clampQuantity(item.quantity + quantity) }
                : item,
            ),
          });
          return;
        }

        // The backend rejects an order with more than 50 lines, so stop at the same limit
        // rather than letting the customer build a basket that cannot be submitted.
        if (items.length >= MAX_ORDER_ITEMS) return;

        set({
          items: [
            ...items,
            {
              productId: product.id,
              quantity: clampQuantity(quantity),
              snapshot: {
                sku: product.sku,
                name: product.name,
                price: product.price,
                currency: product.currency,
                imageUrl: product.imageUrl,
              },
              addedAt: new Date().toISOString(),
            },
          ],
        });
      },

      adoptServerLines: (lines) => {
        const byId = new Map(lines.map((line) => [line.productId, line.quantity]));
        const existing = get().items;

        // Update what is here, and keep anything the server did not mention rather than
        // deleting it — a merge failure mid-flight must not empty somebody's basket.
        const updated = existing.map((item) =>
          byId.has(item.productId)
            ? { ...item, quantity: clampQuantity(byId.get(item.productId)!) }
            : item,
        );

        // Lines the account had that this browser did not. No snapshot yet; `reconcile` fills it
        // in from the catalogue on the next fetch, and until then the basket page shows it as
        // loading rather than inventing a name.
        const known = new Set(existing.map((item) => item.productId));
        const adopted = lines
          .filter((line) => !known.has(line.productId))
          .slice(0, MAX_ORDER_ITEMS - updated.length)
          .map((line) => ({
            productId: line.productId,
            quantity: clampQuantity(line.quantity),
            snapshot: { sku: '', name: '', price: 0, currency: 'EUR', imageUrl: null },
            addedAt: new Date().toISOString(),
          }));

        set({ items: [...updated, ...adopted] });
      },

      setQuantity: (productId, quantity) => {
        if (quantity <= 0) {
          get().remove(productId);
          return;
        }
        set({
          items: get().items.map((item) =>
            item.productId === productId ? { ...item, quantity: clampQuantity(quantity) } : item,
          ),
        });
      },

      remove: (productId) =>
        set({ items: get().items.filter((item) => item.productId !== productId) }),

      clear: () => set({ items: [] }),

      reconcile: (products) =>
        set({
          items: get()
            .items // A product that has vanished from the catalogue cannot be ordered.
            .filter((item) => products.has(item.productId))
            .map((item) => {
              const product = products.get(item.productId)!;
              return {
                ...item,
                // Never silently raise a quantity, but do cap it at what is actually available.
                quantity: Math.min(item.quantity, Math.max(1, product.availableQuantity)),
                snapshot: {
                  sku: product.sku,
                  name: product.name,
                  price: product.price,
                  currency: product.currency,
                  imageUrl: product.imageUrl,
                },
              };
            }),
        }),

      getQuantity: (productId) =>
        get().items.find((item) => item.productId === productId)?.quantity ?? 0,

      itemCount: () => get().items.reduce((total, item) => total + item.quantity, 0),

      distinctCount: () => get().items.length,

      estimatedTotal: () =>
        get().items.reduce((total, item) => total + item.snapshot.price * item.quantity, 0),
    }),
    {
      name: storageKeys.cart,
      storage: createJSONStorage(() => localStorage),
      // Only the data is persisted; the actions come from the factory on rehydrate.
      partialize: (state) => ({ items: state.items }),
      version: 1,
    },
  ),
);

/** Selectors, so a component subscribing to the count does not re-render on a snapshot refresh. */
export const selectCartItems = (state: CartState) => state.items;
export const selectCartCount = (state: CartState) =>
  state.items.reduce((total, item) => total + item.quantity, 0);

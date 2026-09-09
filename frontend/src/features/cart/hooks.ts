import { useEffect, useMemo } from 'react';

import { useProductLookup } from '@/features/product/hooks';
import type { Product } from '@/features/product/types';

import { useCartWriteThrough } from './cart-api';
import { useCartStore } from './store';

export interface PricedCartLine {
  productId: string;
  quantity: number;
  /** Live catalogue record. `null` while loading, or if the product has been withdrawn. */
  product: Product | null;
  /** Authoritative unit price when the product resolved; the stored snapshot otherwise. */
  unitPrice: number;
  currency: string;
  subtotal: number;
  /** The customer wants more than the catalogue can supply right now. */
  exceedsStock: boolean;
  outOfStock: boolean;
}

/**
 * The cart, priced against the live catalogue.
 *
 * This is where the stored snapshot stops being trusted. One batch `POST /api/products/lookup`
 * re-prices every line, caps quantities at real availability and drops withdrawn products — so
 * what the customer sees at checkout is what the backend will price the order at.
 */
export function useCartProducts() {
  const items = useCartStore((state) => state.items);
  const reconcile = useCartStore((state) => state.reconcile);

  const productIds = useMemo(() => items.map((item) => item.productId), [items]);
  const query = useProductLookup(productIds, productIds.length > 0);

  // Fold fresh catalogue data back into the store: refreshed snapshots, capped quantities,
  // withdrawn products removed.
  useEffect(() => {
    if (query.data) {
      reconcile(query.data);
    }
  }, [query.data, reconcile]);

  const lines = useMemo<PricedCartLine[]>(() => {
    const products = query.data;

    return items.map((item) => {
      const product = products?.get(item.productId) ?? null;
      const unitPrice = product?.price ?? item.snapshot.price;
      const currency = product?.currency ?? item.snapshot.currency;

      return {
        productId: item.productId,
        quantity: item.quantity,
        product,
        unitPrice,
        currency,
        subtotal: unitPrice * item.quantity,
        exceedsStock: Boolean(product && item.quantity > product.availableQuantity),
        outOfStock: Boolean(product && !product.inStock),
      };
    });
  }, [items, query.data]);

  const total = useMemo(
    () => lines.reduce((sum, line) => sum + line.subtotal, 0),
    [lines],
  );

  const currency = lines[0]?.currency ?? 'EUR';

  /**
   * The backend rejects an order that mixes currencies, so catch it here rather than letting the
   * customer fill in an address and then fail at submit.
   */
  const hasMixedCurrencies = useMemo(
    () => new Set(lines.map((line) => line.currency)).size > 1,
    [lines],
  );

  const blockingIssues = useMemo(() => {
    const issues: string[] = [];
    if (lines.some((line) => line.outOfStock)) {
      issues.push('One or more items are out of stock.');
    }
    if (lines.some((line) => line.exceedsStock)) {
      issues.push('One or more items exceed the quantity currently available.');
    }
    if (hasMixedCurrencies) {
      issues.push('An order cannot mix currencies. Please split it into separate orders.');
    }
    return issues;
  }, [lines, hasMixedCurrencies]);

  return {
    lines,
    total,
    currency,
    itemCount: lines.reduce((sum, line) => sum + line.quantity, 0),
    isEmpty: items.length === 0,
    isLoading: query.isLoading,
    isRefetching: query.isRefetching,
    error: query.error,
    blockingIssues,
    canCheckout: items.length > 0 && blockingIssues.length === 0 && !query.isLoading,
    refetch: query.refetch,
  };
}

/**
 * Basket mutations that also reach the server.
 *
 * The screen updates from the local store immediately; the write-through follows and is silent
 * about both success and failure. That ordering is the point — a quantity control that waits for
 * a round trip feels broken on a train, and a toast saying a background write failed is reporting
 * our bookkeeping as if it were the customer's problem.
 *
 * Use this rather than `useCartStore` directly anywhere a customer changes their basket. Reading
 * from the store is fine; writing to it without this leaves the account's copy behind.
 */
export function useCartActions() {
  const add = useCartStore((state) => state.add);
  const setQuantity = useCartStore((state) => state.setQuantity);
  const remove = useCartStore((state) => state.remove);
  const clear = useCartStore((state) => state.clear);
  const quantityOf = useCartStore((state) => state.getQuantity);
  const { write } = useCartWriteThrough();

  return {
    add: (product: Product, quantity = 1) => {
      add(product, quantity);
      // The local store clamps and merges; read back what it settled on rather than assuming,
      // so the server is told the same number the customer is looking at.
      write(product.id, useCartStore.getState().getQuantity(product.id));
    },

    setQuantity: (productId: string, quantity: number) => {
      setQuantity(productId, quantity);
      write(productId, useCartStore.getState().getQuantity(productId));
    },

    remove: (productId: string) => {
      remove(productId);
      write(productId, 0);
    },

    clear: () => {
      const ids = useCartStore.getState().items.map((item) => item.productId);
      clear();
      ids.forEach((id) => write(id, 0));
    },

    quantityOf,
  };
}

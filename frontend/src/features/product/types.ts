import type { PageParams } from '@/types/api';

/** DTOs for Inventory Service. Mirrors `com.commerceflow.inventoryservice.dto`. */

export interface Product {
  id: string;
  sku: string;
  name: string;
  description: string | null;
  /** Which section it is filed under, or null when it is unfiled. */
  categoryId: string | null;
  /**
   * The section's stable identifier.
   *
   * Filters key on this, never on the display name — a merchandiser renaming "Computers" must not
   * break a bookmarked filter, and on the server it must not change the tax the products attract.
   */
  categorySlug: string | null;
  /** The section's display name, which may be renamed at any time. */
  category: string | null;
  price: number;
  currency: string;
  imageUrl: string | null;
  active: boolean;
  /** Units a new order may consume. */
  availableQuantity: number;
  /** Units held by orders whose saga is still running. Never sold twice. */
  reservedQuantity: number;
  inStock: boolean;
  /**
   * Average of the published reviews, or null when there are none.
   *
   * Null rather than zero: "no reviews yet" and "reviewed, and terrible" are different things,
   * and a zero renders as one star.
   */
  ratingAverage: number | null;
  ratingCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface ProductSearchParams extends PageParams {
  /** A category **slug**, not a display name. */
  category?: string;
  /**
   * Full-text search over name, SKU and description, ranked by relevance.
   *
   * Server-side this is a Postgres `tsquery` with the last word treated as a prefix, so results
   * appear while somebody is still typing. There is no typo tolerance: "labtop" finds nothing.
   */
  search?: string;
  activeOnly?: boolean;
}

export interface CreateProductPayload {
  sku: string;
  name: string;
  description?: string;
  /** Send one of these. A section has to exist first, so a typo is an error not a fourth spelling. */
  categoryId?: string;
  categorySlug?: string;
  price: number;
  currency?: string;
  imageUrl?: string;
  initialQuantity: number;
  reorderLevel?: number;
}

/**
 * What an edit can change.
 *
 * No `sku` and no quantity: the SKU is how everything outside this platform refers to the
 * product, and stock has its own endpoint that never touches units a running order is holding.
 * `active: false` is how a product comes off the storefront — there is no delete, because orders
 * reference products and a customer has a receipt for what they bought.
 */
export interface UpdateProductPayload {
  name: string;
  description?: string;
  /** Send one of these. Both omitted leaves the product unfiled. */
  categoryId?: string;
  categorySlug?: string;
  price: number;
  imageUrl?: string;
  active: boolean;
}

export type StockOperation = 'SET' | 'INCREASE' | 'DECREASE';

export interface UpdateStockPayload {
  quantity: number;
  operation?: StockOperation;
  reason?: string;
}

/** Stock bands, so the badge and the messaging agree everywhere. */
export type StockLevel = 'out' | 'low' | 'in';

export const LOW_STOCK_THRESHOLD = 5;

export function stockLevel(product: Product): StockLevel {
  if (!product.inStock || product.availableQuantity <= 0) return 'out';
  if (product.availableQuantity <= LOW_STOCK_THRESHOLD) return 'low';
  return 'in';
}

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import type { PageResponse } from '@/types/api';

import type {
  CreateProductPayload,
  Product,
  ProductSearchParams,
  UpdateProductPayload,
  UpdateStockPayload,
} from './types';

export const productApi = {
  /** Paged catalogue search. Every filter is optional; the backend ignores unknown sort fields. */
  search: (params: ProductSearchParams = {}): Promise<PageResponse<Product>> =>
    api.get<PageResponse<Product>>(endpoints.products.list, { params }),

  getById: (id: string): Promise<Product> => api.get<Product>(endpoints.products.byId(id)),

  getBySku: (sku: string): Promise<Product> => api.get<Product>(endpoints.products.bySku(sku)),

  /**
   * Batch lookup, max 100 ids.
   *
   * This is what makes the cart cheap: re-pricing a basket of twelve items is one request, not
   * twelve. Unknown ids are omitted from the response rather than failing it — so a product
   * deleted since it was added to the cart shows up as a missing entry, which the cart handles.
   */
  lookup: (productIds: string[]): Promise<Product[]> =>
    productIds.length === 0
      ? Promise.resolve([])
      : api.post<Product[]>(endpoints.products.lookup, { productIds }),

  // ---- admin -----------------------------------------------------------------------------

  create: (payload: CreateProductPayload): Promise<Product> =>
    api.post<Product>(endpoints.products.create, payload),

  update: (id: string, payload: UpdateProductPayload): Promise<Product> =>
    api.put<Product>(endpoints.products.update(id), payload),

  updateStock: (id: string, payload: UpdateStockPayload): Promise<Product> =>
    api.put<Product>(endpoints.products.stock(id), payload),
};

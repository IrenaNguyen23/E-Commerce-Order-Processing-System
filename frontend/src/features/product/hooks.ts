import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { normalizeError } from '@/services/error';
import { staleTimes } from '@/shared/config';

import { categoryApi } from './categories-api';
import { productApi } from './api';
import type {
  CreateProductPayload,
  Product,
  ProductSearchParams,
  UpdateProductPayload,
  UpdateStockPayload,
} from './types';

/**
 * Query keys, built from one factory.
 *
 * A hierarchy (`['products']` → `['products','list',params]`) means a mutation can invalidate
 * every product list with a single prefix, without knowing which filters happen to be cached.
 */
export const productKeys = {
  all: ['products'] as const,
  lists: () => [...productKeys.all, 'list'] as const,
  list: (params: ProductSearchParams) => [...productKeys.lists(), params] as const,
  details: () => [...productKeys.all, 'detail'] as const,
  detail: (id: string) => [...productKeys.details(), id] as const,
  lookup: (ids: string[]) => [...productKeys.all, 'lookup', [...ids].sort()] as const,
  categories: () => [...productKeys.all, 'categories'] as const,
};

/** Paged catalogue. `staleTime` is generous — the catalogue changes far less often than it is read. */
export function useProducts(params: ProductSearchParams) {
  return useQuery({
    queryKey: productKeys.list(params),
    queryFn: () => productApi.search(params),
    staleTime: staleTimes.catalogue,
    // Keeps the previous page visible while the next one loads, so the grid does not blank out
    // and the page does not jump on every pagination click.
    placeholderData: (previous) => previous,
  });
}

export function useProduct(id: string | undefined) {
  return useQuery({
    queryKey: productKeys.detail(id ?? ''),
    queryFn: () => productApi.getById(id!),
    enabled: Boolean(id),
    staleTime: staleTimes.catalogue,
  });
}

/**
 * Batch lookup, used by the cart and wishlist to re-price stored ids.
 *
 * Returned as a `Map` via `select` so the computation happens once per cache entry rather than
 * on every render of every consumer.
 */
export function useProductLookup(productIds: string[], enabled = true) {
  return useQuery({
    queryKey: productKeys.lookup(productIds),
    queryFn: () => productApi.lookup(productIds),
    enabled: enabled && productIds.length > 0,
    staleTime: 60_000,
    select: (products) => new Map(products.map((product) => [product.id, product])),
  });
}

/**
 * The category list.
 *
 * Inventory Service has no category endpoint — `category` is a string column. So the list is
 * derived: fetch a wide page of active products and collect the distinct values. Cheap, correct
 * for a catalogue of this size, and honest about what the backend actually models.
 *
 * If the catalogue ever outgrows a single page, this is the first thing that needs a real
 * `GET /api/categories` endpoint.
 */
/**
 * The catalogue's sections.
 *
 * <p>Read from the category endpoint, which is a real table with real names and counts. This used
 * to fetch a hundred products and count the distinct values of a free-text column — which gave
 * four spellings of "Computers", a count that stopped at whatever the page size was, and no way
 * to order the sections the way a shop wanted them.
 */
export function useCategories(includeInactive = false) {
  return useQuery({
    queryKey: [...productKeys.categories(), includeInactive],
    queryFn: () => categoryApi.flat(includeInactive),
    staleTime: staleTimes.catalogue,
  });
}

/** The menu: top-level sections with their subsections nested inside. */
export function useCategoryTree(includeInactive = false) {
  return useQuery({
    queryKey: [...productKeys.categories(), 'tree', includeInactive],
    queryFn: () => categoryApi.tree(includeInactive),
    staleTime: staleTimes.catalogue,
  });
}

// ---------------------------------------------------------------------------------------------
// Admin mutations
// ---------------------------------------------------------------------------------------------

export function useCreateProduct() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (payload: CreateProductPayload) => productApi.create(payload),
    onSuccess: (product) => {
      // One prefix invalidation covers every cached filter combination.
      void queryClient.invalidateQueries({ queryKey: productKeys.lists() });
      void queryClient.invalidateQueries({ queryKey: productKeys.categories() });
      queryClient.setQueryData(productKeys.detail(product.id), product);
      toast.success(`${product.name} added to the catalogue`);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useUpdateProduct() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateProductPayload }) =>
      productApi.update(id, payload),
    onSuccess: (product) => {
      void queryClient.invalidateQueries({ queryKey: productKeys.lists() });
      // The category list is derived from the catalogue, so an edit that moves a product to a
      // new category has to invalidate it too or the nav goes stale.
      void queryClient.invalidateQueries({ queryKey: productKeys.categories() });
      queryClient.setQueryData(productKeys.detail(product.id), product);
      toast.success(
        product.active ? `${product.name} updated` : `${product.name} is no longer on sale`,
      );
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useUpdateStock() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateStockPayload }) =>
      productApi.updateStock(id, payload),
    onSuccess: (product: Product) => {
      queryClient.setQueryData(productKeys.detail(product.id), product);
      void queryClient.invalidateQueries({ queryKey: productKeys.lists() });
      toast.success(`Stock for ${product.sku} is now ${product.availableQuantity}`);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

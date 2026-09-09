import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { productKeys } from '@/features/product/hooks';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

/**
 * Warehouses and what is in them.
 *
 * <h2>`stock_levels` is the truth; a product's quantity is a summary of it</h2>
 *
 * Worth carrying into the UI, because it decides which number an operator should believe. Setting
 * stock here changes the authoritative row and the product-level total is recomputed from it in the
 * same transaction — so after any mutation the product lists are stale and are invalidated too.
 *
 * The reverse is not true: the stock endpoint on a product writes the summary and is the older,
 * single-warehouse path. Anywhere a building matters, use these.
 */

export interface Warehouse {
  id: string;
  /** Short stable identifier. Immutable once the warehouse exists. */
  code: string;
  name: string;
  countryCode: string;
  city: string | null;
  /** Lower goes first when more than one building could fill a line. */
  priority: number;
  /** Whether **new** orders may be allocated from here. Existing reservations are untouched. */
  active: boolean;
  updatedAt: string;
}

export interface WarehousePayload {
  code: string;
  name: string;
  countryCode: string;
  city?: string;
  priority?: number;
  active?: boolean;
}

export interface StockLevel {
  warehouseId: string;
  productId: string;
  sku: string | null;
  /** Units a new order may take from this building. */
  availableQuantity: number;
  /** Units held here by an order whose saga is still running. Never editable. */
  reservedQuantity: number;
  reorderLevel: number;
  belowReorderLevel: boolean;
  updatedAt: string;
}

export const warehouseKeys = {
  all: ['warehouses'] as const,
  list: (includeInactive: boolean) => [...warehouseKeys.all, 'list', includeInactive] as const,
  contents: (id: string) => [...warehouseKeys.all, 'contents', id] as const,
  locate: (productId: string) => [...warehouseKeys.all, 'locate', productId] as const,
  low: () => [...warehouseKeys.all, 'low'] as const,
};

export const warehouseApi = {
  list: (includeInactive = false): Promise<Warehouse[]> =>
    api.get<Warehouse[]>(endpoints.warehouses.list, { params: { includeInactive } }),

  create: (payload: WarehousePayload): Promise<Warehouse> =>
    api.post<Warehouse>(endpoints.warehouses.list, payload),

  update: (id: string, payload: WarehousePayload): Promise<Warehouse> =>
    api.put<Warehouse>(endpoints.warehouses.byId(id), payload),

  contents: (id: string): Promise<StockLevel[]> =>
    api.get<StockLevel[]>(endpoints.warehouses.stock(id)),

  locate: (productId: string): Promise<StockLevel[]> =>
    api.get<StockLevel[]>(endpoints.warehouses.locate(productId)),

  lowStock: (): Promise<StockLevel[]> => api.get<StockLevel[]>(endpoints.warehouses.lowStock),

  setStock: (
    warehouseId: string,
    productId: string,
    quantity: number,
    reorderLevel?: number,
  ): Promise<StockLevel> =>
    api.put<StockLevel>(endpoints.warehouses.setStock(warehouseId, productId), null, {
      params: { quantity, reorderLevel },
    }),
};

export function useWarehouses(includeInactive = false) {
  return useQuery({
    queryKey: warehouseKeys.list(includeInactive),
    queryFn: () => warehouseApi.list(includeInactive),
  });
}

export function useWarehouseContents(warehouseId: string | undefined) {
  return useQuery({
    queryKey: warehouseKeys.contents(warehouseId ?? ''),
    queryFn: () => warehouseApi.contents(warehouseId!),
    enabled: Boolean(warehouseId),
  });
}

/** Where one product is, across every building. */
export function useProductLocations(productId: string | undefined) {
  return useQuery({
    queryKey: warehouseKeys.locate(productId ?? ''),
    queryFn: () => warehouseApi.locate(productId!),
    enabled: Boolean(productId),
  });
}

export function useLowStock() {
  return useQuery({
    queryKey: warehouseKeys.low(),
    queryFn: warehouseApi.lowStock,
  });
}

function useWarehouseMutation<TVariables, TData>(
  mutationFn: (variables: TVariables) => Promise<TData>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: warehouseKeys.all });
      // The product-level total is recomputed from the warehouse rows, so every product listing
      // is stale after a stock change here.
      void queryClient.invalidateQueries({ queryKey: productKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useCreateWarehouse() {
  return useWarehouseMutation(
    (payload: WarehousePayload) => warehouseApi.create(payload),
    'Warehouse opened',
  );
}

export function useUpdateWarehouse() {
  return useWarehouseMutation(
    ({ id, payload }: { id: string; payload: WarehousePayload }) =>
      warehouseApi.update(id, payload),
    'Warehouse updated',
  );
}

export function useSetWarehouseStock() {
  return useWarehouseMutation(
    ({ warehouseId, productId, quantity, reorderLevel }: {
      warehouseId: string;
      productId: string;
      quantity: number;
      reorderLevel?: number;
    }) => warehouseApi.setStock(warehouseId, productId, quantity, reorderLevel),
    'Stock updated',
  );
}

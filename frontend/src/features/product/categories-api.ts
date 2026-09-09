import { useMutation, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

import { productKeys } from './hooks';

/**
 * Catalogue sections.
 *
 * <h2>Slugs, not names</h2>
 *
 * Everything that filters, links or bookmarks uses `slug`. The `name` is a label a merchandiser
 * can change on a whim, and a filter keyed on it breaks every saved link the day somebody renames
 * "Computers" to "Laptops & desktops". On the server the same rule is load-bearing for a
 * different reason: tax rates are looked up by category, so a renamed slug would change what the
 * products inside it are charged.
 */

export interface Category {
  id: string;
  slug: string;
  name: string;
  description: string | null;
  parentId: string | null;
  position: number;
  imageUrl: string | null;
  active: boolean;
  /** How many products are filed under it. Zero is what makes deleting possible. */
  productCount: number;
  children: Category[];
}

export interface CategoryPayload {
  name: string;
  /** Derived from the name when omitted. Cannot be changed once the category exists. */
  slug?: string;
  description?: string;
  parentId?: string;
  position?: number;
  imageUrl?: string;
  active?: boolean;
}

export const categoryApi = {
  tree: (includeInactive = false): Promise<Category[]> =>
    api.get<Category[]>(endpoints.categories.tree, { params: { includeInactive } }),

  flat: (includeInactive = false): Promise<Category[]> =>
    api.get<Category[]>(endpoints.categories.flat, { params: { includeInactive } }),

  create: (payload: CategoryPayload): Promise<Category> =>
    api.post<Category>(endpoints.categories.tree, payload),

  update: (id: string, payload: CategoryPayload): Promise<Category> =>
    api.put<Category>(endpoints.categories.byId(id), payload),

  remove: (id: string): Promise<void> => api.delete<void>(endpoints.categories.byId(id)),
};

function useCategoryMutation<TVariables, TData>(
  mutationFn: (variables: TVariables) => Promise<TData>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      // Products carry their category's name, so both listings are stale.
      void queryClient.invalidateQueries({ queryKey: productKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useCreateCategory() {
  return useCategoryMutation(
    (payload: CategoryPayload) => categoryApi.create(payload),
    'Category created',
  );
}

export function useUpdateCategory() {
  return useCategoryMutation(
    ({ id, payload }: { id: string; payload: CategoryPayload }) => categoryApi.update(id, payload),
    'Category updated',
  );
}

/**
 * Removes an empty section.
 *
 * Refused by the server while products or subsections are still filed under it. The message says
 * to hide it instead, which is what an operator almost always meant — and which leaves old orders
 * able to name where they came from.
 */
export function useDeleteCategory() {
  return useCategoryMutation((id: string) => categoryApi.remove(id), 'Category deleted');
}

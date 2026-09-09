import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { productKeys } from '@/features/product/hooks';
import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

/**
 * A product's photographs.
 *
 * <h2>The URL is opaque and permanent</h2>
 *
 * An image's bytes never change — editing means uploading a new one, which gets a new id and a new
 * URL. That is what lets the backend cache them for a year and mark them immutable, and it is why
 * nothing here ever appends a cache-busting query parameter: doing so would defeat the caching
 * without fixing anything.
 *
 * <h2>The declared type is not sent as a claim</h2>
 *
 * The browser fills in a `Content-Type` for the part and the server ignores it, deciding from the
 * bytes instead. Worth knowing here because it explains the error message: a file rejected as "not
 * a JPEG, PNG or WebP" was rejected on its contents, so renaming it will not help.
 */

export interface ProductImage {
  id: string;
  /** A path, not an absolute address — the same response works behind the gateway or locally. */
  url: string;
  contentType: string;
  sizeBytes: number;
  altText: string | null;
  position: number;
  /** True for the image shown on a listing tile. */
  primary: boolean;
  createdAt: string;
}

export const productImageKeys = {
  all: ['product-images'] as const,
  forProduct: (productId: string) => [...productImageKeys.all, productId] as const,
};

export const productImageApi = {
  list: (productId: string): Promise<ProductImage[]> =>
    api.get<ProductImage[]>(endpoints.productImages.list(productId)),

  upload: (productId: string, file: File, altText?: string): Promise<ProductImage> => {
    const body = new FormData();
    body.append('file', file);
    if (altText) {
      body.append('altText', altText);
    }
    // The shared client sets `Content-Type: application/json` by default. For multipart that
    // header has to go: the browser generates a boundary and puts it in the header itself, and a
    // request that says JSON while carrying a multipart body is one the server cannot parse — a
    // 400 or a 415 pointing at nothing in particular.
    return api.post<ProductImage>(endpoints.productImages.upload(productId), body, {
      headers: { 'Content-Type': undefined },
    });
  },

  makePrimary: (productId: string, imageId: string): Promise<ProductImage[]> =>
    api.post<ProductImage[]>(endpoints.productImages.primary(productId, imageId), {}),

  remove: (productId: string, imageId: string): Promise<void> =>
    api.delete<void>(endpoints.productImages.byId(productId, imageId)),
};

export function useProductImages(productId: string | undefined) {
  return useQuery({
    queryKey: productImageKeys.forProduct(productId ?? ''),
    queryFn: () => productImageApi.list(productId!),
    enabled: Boolean(productId),
  });
}

function useImageMutation<TVariables, TData>(
  productId: string,
  mutationFn: (variables: TVariables) => Promise<TData>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: productImageKeys.forProduct(productId) });
      // A product's tile image comes from its first upload, so the catalogue is stale too.
      void queryClient.invalidateQueries({ queryKey: productKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useUploadProductImage(productId: string) {
  return useImageMutation(
    productId,
    ({ file, altText }: { file: File; altText?: string }) =>
      productImageApi.upload(productId, file, altText),
    'Image uploaded',
  );
}

export function useMakeImagePrimary(productId: string) {
  return useImageMutation(
    productId,
    (imageId: string) => productImageApi.makePrimary(productId, imageId),
    'Tile image updated',
  );
}

export function useDeleteProductImage(productId: string) {
  return useImageMutation(
    productId,
    (imageId: string) => productImageApi.remove(productId, imageId),
    'Image deleted',
  );
}

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';

/**
 * The customer's address book, on the server.
 *
 * This replaces a `localStorage` book that could not follow anyone between devices. The important
 * property of the new one is what it *is not*: an order never points at these rows. It copies the
 * fields at checkout, so editing a typo here can never rewrite where last year's parcels went, and
 * deleting an address never orphans an order.
 *
 * Nothing here takes a user id. The token supplies it, and an endpoint that accepted one is an
 * endpoint somebody would eventually call with a different one.
 */

export type AddressType = 'SHIPPING' | 'BILLING' | 'BOTH';

export interface Address {
  id: string;
  label: string | null;
  type: AddressType;
  recipientName: string;
  phone: string | null;
  line1: string;
  line2: string | null;
  city: string;
  region: string | null;
  postalCode: string | null;
  /** ISO-3166 alpha-2. This is what decides the tax rate and the delivery charge. */
  countryCode: string;
  /** One line, formatted by the backend so every surface prints it identically. */
  formatted: string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AddressPayload {
  label?: string;
  type?: AddressType;
  recipientName: string;
  phone?: string;
  line1: string;
  line2?: string;
  city: string;
  region?: string;
  postalCode?: string;
  countryCode: string;
  makeDefault?: boolean;
}

export const addressKeys = {
  all: ['addresses'] as const,
  list: () => [...addressKeys.all, 'list'] as const,
};

export const addressApi = {
  list: (): Promise<Address[]> => api.get<Address[]>(endpoints.addresses.list),

  create: (payload: AddressPayload): Promise<Address> =>
    api.post<Address>(endpoints.addresses.create, payload),

  update: (id: string, payload: AddressPayload): Promise<Address> =>
    api.put<Address>(endpoints.addresses.byId(id), payload),

  makeDefault: (id: string): Promise<Address> =>
    api.post<Address>(endpoints.addresses.makeDefault(id), {}),

  remove: (id: string): Promise<void> => api.delete<void>(endpoints.addresses.byId(id)),
};

export function useAddresses(enabled = true) {
  return useQuery({
    queryKey: addressKeys.list(),
    queryFn: addressApi.list,
    enabled,
    // Addresses change rarely and are read on every checkout. Anything shorter would be a
    // request per page view for data that almost never moves.
    staleTime: 5 * 60 * 1000,
  });
}

/** The address a checkout form should pre-select, or `null` when the book is empty. */
export function useDefaultAddress(enabled = true) {
  const query = useAddresses(enabled);
  return {
    ...query,
    data: query.data?.find((address) => address.isDefault) ?? query.data?.[0] ?? null,
  };
}

function useAddressMutation<TVariables, TData>(
  mutationFn: (variables: TVariables) => Promise<TData>,
  successMessage: string,
) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn,
    onSuccess: () => {
      // Refetch rather than patch the cache by hand. Saving an address can silently change a
      // *different* row — promoting one demotes another, and deleting the default promotes a
      // successor — so a local edit would leave the list disagreeing with the server.
      void queryClient.invalidateQueries({ queryKey: addressKeys.all });
      toast.success(successMessage);
    },
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

export function useCreateAddress() {
  return useAddressMutation(
    (payload: AddressPayload) => addressApi.create(payload),
    'Address saved',
  );
}

export function useUpdateAddress() {
  return useAddressMutation(
    ({ id, payload }: { id: string; payload: AddressPayload }) => addressApi.update(id, payload),
    'Address updated',
  );
}

export function useMakeDefaultAddress() {
  return useAddressMutation((id: string) => addressApi.makeDefault(id), 'Default address set');
}

export function useDeleteAddress() {
  return useAddressMutation((id: string) => addressApi.remove(id), 'Address deleted');
}

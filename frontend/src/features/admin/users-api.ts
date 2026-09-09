import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';

import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import { normalizeError } from '@/services/error';
import type { PageResponse } from '@/types/api';

/**
 * Account administration.
 *
 * Kept in the admin feature rather than in `features/auth`, which is about the signed-in visitor
 * proving who they are. This is somebody else changing what a third party can do, and mixing the
 * two would put an endpoint only administrators may call next to the ones everybody calls.
 */

export interface AdminUser {
  id: string;
  email: string;
  fullName: string;
  phone: string | null;
  roles: string[];
  enabled: boolean;
  createdAt: string;
}

export interface UserSearchParams {
  search?: string;
  role?: string;
  enabled?: boolean;
  page?: number;
  size?: number;
  sortBy?: string;
  direction?: 'asc' | 'desc';
}

/** Omitted fields are left alone — the backend treats this as a PATCH, and so does the UI. */
export interface UpdateUserPayload {
  enabled?: boolean;
  roles?: string[];
}

export const userKeys = {
  all: ['admin-users'] as const,
  lists: () => [...userKeys.all, 'list'] as const,
  list: (params: UserSearchParams) => [...userKeys.lists(), params] as const,
};

export const userAdminApi = {
  search: (params: UserSearchParams): Promise<PageResponse<AdminUser>> =>
    api.get<PageResponse<AdminUser>>(endpoints.users.list, { params }),

  update: (id: string, payload: UpdateUserPayload): Promise<AdminUser> =>
    api.patch<AdminUser>(endpoints.users.byId(id), payload),
};

export function useAdminUsers(params: UserSearchParams) {
  return useQuery({
    queryKey: userKeys.list(params),
    queryFn: () => userAdminApi.search(params),
  });
}

export function useUpdateUser() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({ id, payload }: { id: string; payload: UpdateUserPayload }) =>
      userAdminApi.update(id, payload),
    onSuccess: (user) => {
      void queryClient.invalidateQueries({ queryKey: userKeys.lists() });
      toast.success(
        user.enabled ? `${user.email} updated` : `${user.email} can no longer sign in`,
      );
    },
    // The refusals here are the interesting ones — "you cannot disable your own account", "this
    // is the last administrator" — and the server's wording explains them better than anything
    // generic would.
    onError: (error) => toast.error(normalizeError(error).message),
  });
}

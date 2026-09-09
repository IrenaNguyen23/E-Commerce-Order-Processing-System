import { useQuery } from '@tanstack/react-query';

import { notificationApi } from './api';
import type { NotificationSearchParams } from './types';

export const notificationKeys = {
  all: ['notifications'] as const,
  lists: () => [...notificationKeys.all, 'list'] as const,
  list: (params: NotificationSearchParams) => [...notificationKeys.lists(), params] as const,
  detail: (id: string) => [...notificationKeys.all, 'detail', id] as const,
};

export function useNotifications(params: NotificationSearchParams, enabled = true) {
  return useQuery({
    queryKey: notificationKeys.list(params),
    queryFn: () => notificationApi.search(params),
    enabled,
    placeholderData: (previous) => previous,
  });
}

/**
 * Unread-style badge count for the header.
 *
 * The backend has no read/unread concept, so this is simply "recent activity": the total the
 * list endpoint reports. Refetched on a slow interval — a notification arriving a minute late
 * costs nothing, and polling harder would be noise.
 */
export function useNotificationCount(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.list({ page: 0, size: 1 }),
    queryFn: () => notificationApi.search({ page: 0, size: 1 }),
    enabled,
    refetchInterval: 60_000,
    select: (page) => page.totalElements,
  });
}

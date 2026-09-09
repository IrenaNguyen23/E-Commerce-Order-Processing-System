import { api } from '@/services/api-client';
import { endpoints } from '@/services/endpoints';
import type { PageResponse } from '@/types/api';

import type { Notification, NotificationSearchParams } from './types';

export const notificationApi = {
  search: (params: NotificationSearchParams = {}): Promise<PageResponse<Notification>> =>
    api.get<PageResponse<Notification>>(endpoints.notifications.list, { params }),

  getById: (id: string): Promise<Notification> =>
    api.get<Notification>(endpoints.notifications.byId(id)),
};

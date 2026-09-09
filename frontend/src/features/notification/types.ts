import type { PageParams } from '@/types/api';

/** DTOs for Notification Service. Mirrors `com.commerceflow.notificationservice.dto`. */

export type NotificationStatus = 'PENDING' | 'SENT' | 'FAILED';
export type NotificationChannel = 'EMAIL' | 'SMS' | 'PUSH';
export type NotificationType =
  | 'USER_WELCOME'
  | 'ORDER_CONFIRMED'
  | 'ORDER_CANCELLED'
  | 'GENERIC';

export interface Notification {
  id: string;
  userId: string | null;
  /** The entity this is about — typically an order id, which is what makes it linkable. */
  referenceId: string | null;
  type: NotificationType;
  channel: NotificationChannel;
  recipient: string;
  subject: string;
  content: string;
  status: NotificationStatus;
  failureReason: string | null;
  retryCount: number;
  createdAt: string;
  sentAt: string | null;
}

export interface NotificationSearchParams extends PageParams {
  status?: NotificationStatus | '';
}

/** Only order notifications carry an order id worth linking to. */
export const isOrderNotification = (notification: Notification): boolean =>
  notification.type === 'ORDER_CONFIRMED' || notification.type === 'ORDER_CANCELLED';

import { Bell, Mail, MessageSquare, Smartphone } from 'lucide-react';
import { Link } from 'react-router-dom';

import { PageHeader } from '@/components/common/page-header';
import { PagePagination } from '@/components/common/pagination';
import { EmptyState, ErrorState } from '@/components/common/states';
import { NotificationStatusBadge } from '@/components/common/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { useQueryParams } from '@/hooks/use-query-params';
import { paths } from '@/routes/paths';
import { normalizeError } from '@/services/error';
import { NOTIFICATION_STATUSES } from '@/utils/constants';
import { formatRelative } from '@/utils/format';

import { useNotifications } from '../hooks';
import { isOrderNotification, type Notification, type NotificationStatus } from '../types';

const DEFAULTS = { page: 0, size: 20, status: '' };
const ALL_STATUSES = '__all__';

const CHANNEL_ICONS = {
  EMAIL: Mail,
  SMS: MessageSquare,
  PUSH: Smartphone,
} as const;

export default function NotificationsPage() {
  const { params, setParams } = useQueryParams(DEFAULTS);

  const query = useNotifications({
    page: params.page,
    size: params.size,
    status: (params.status || undefined) as NotificationStatus | undefined,
  });

  const notifications = query.data?.content;

  return (
    <div>
      <PageHeader
        title="Notifications"
        description="Everything the platform has sent you."
        breadcrumbs={[{ label: 'Home', to: paths.home }, { label: 'Notifications' }]}
        actions={
          <Select
            value={params.status || ALL_STATUSES}
            onValueChange={(status) =>
              setParams({ status: status === ALL_STATUSES ? '' : status, page: 0 })
            }
          >
            <SelectTrigger className="w-44" aria-label="Filter by status">
              <SelectValue placeholder="All" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL_STATUSES}>All</SelectItem>
              {NOTIFICATION_STATUSES.map((status) => (
                <SelectItem key={status} value={status}>
                  {status.toLowerCase()}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        }
      />

      {query.isError ? (
        <ErrorState error={normalizeError(query.error)} onRetry={() => void query.refetch()} />
      ) : query.isLoading ? (
        <div className="space-y-3">
          {Array.from({ length: 5 }, (_, index) => (
            <Skeleton key={index} className="h-24 rounded-lg" />
          ))}
        </div>
      ) : !notifications || notifications.length === 0 ? (
        <EmptyState
          icon={<Bell className="h-10 w-10" />}
          title="Nothing here yet"
          description="Order confirmations and account messages will appear here."
          action={
            <Button asChild>
              <Link to={paths.products}>Browse the catalogue</Link>
            </Button>
          }
        />
      ) : (
        <div className="space-y-3">
          {notifications.map((notification) => (
            <NotificationCard key={notification.id} notification={notification} />
          ))}

          <PagePagination
            data={query.data}
            onPageChange={(page) => setParams({ page })}
            className="pt-4"
          />
        </div>
      )}
    </div>
  );
}

function NotificationCard({ notification }: { notification: Notification }) {
  const ChannelIcon = CHANNEL_ICONS[notification.channel] ?? Bell;

  return (
    <Card>
      <CardContent className="flex gap-4 p-5">
        <span
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground"
          aria-hidden
        >
          <ChannelIcon className="h-4 w-4" />
        </span>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-sm font-medium">{notification.subject}</p>
            <NotificationStatusBadge status={notification.status} showIcon={false} />
          </div>

          {/* Bodies are plain text with real newlines — `whitespace-pre-line` keeps the
              paragraphing the backend template produced. */}
          <p className="mt-2 whitespace-pre-line text-sm text-muted-foreground">
            {notification.content}
          </p>

          <div className="mt-3 flex flex-wrap items-center gap-3 text-xs text-muted-foreground">
            <span>{formatRelative(notification.sentAt ?? notification.createdAt)}</span>
            <span>·</span>
            <span>{notification.recipient}</span>

            {isOrderNotification(notification) && notification.referenceId ? (
              <>
                <span>·</span>
                <Link
                  to={paths.order(notification.referenceId)}
                  className="font-medium text-foreground underline-offset-4 hover:underline"
                >
                  View order
                </Link>
              </>
            ) : null}
          </div>

          {notification.failureReason ? (
            <p className="mt-2 text-xs text-destructive">
              Delivery failed: {notification.failureReason}
            </p>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

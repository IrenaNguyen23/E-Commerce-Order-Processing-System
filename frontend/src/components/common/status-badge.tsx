import { CheckCircle2, Clock, Loader2, XCircle } from 'lucide-react';

import { Badge, type BadgeProps } from '@/components/ui/badge';
import type { NotificationStatus } from '@/features/notification/types';
import type { OrderStatus } from '@/features/order/types';
import type { PaymentStatus } from '@/features/payment/types';
import { cn } from '@/utils/cn';

type Variant = NonNullable<BadgeProps['variant']>;

interface Descriptor {
  label: string;
  variant: Variant;
  /** Spins while the saga is mid-flight, so an in-progress order reads as *moving*. */
  pulse?: boolean;
}

/**
 * Status wording lives here, once.
 *
 * The backend's enum names are precise but not customer-facing — `INVENTORY_RESERVED` means
 * nothing to a shopper. Mapping in one place keeps the label, the colour and the icon consistent
 * across the order list, the detail page and the admin table.
 */
const ORDER_STATUS: Record<OrderStatus, Descriptor> = {
  CREATED: { label: 'Placed', variant: 'secondary', pulse: true },
  INVENTORY_RESERVED: { label: 'Stock reserved', variant: 'secondary', pulse: true },
  PAID: { label: 'Paid', variant: 'secondary', pulse: true },
  COMPLETED: { label: 'Confirmed', variant: 'success' },
  CANCELLED: { label: 'Cancelled', variant: 'destructive' },
};

const PAYMENT_STATUS: Record<PaymentStatus, Descriptor> = {
  PENDING: { label: 'Pending', variant: 'secondary', pulse: true },
  COMPLETED: { label: 'Paid', variant: 'success' },
  FAILED: { label: 'Declined', variant: 'destructive' },
  REFUNDED: { label: 'Refunded', variant: 'warning' },
};

const NOTIFICATION_STATUS: Record<NotificationStatus, Descriptor> = {
  PENDING: { label: 'Queued', variant: 'secondary', pulse: true },
  SENT: { label: 'Sent', variant: 'success' },
  FAILED: { label: 'Not delivered', variant: 'destructive' },
};

function StatusBadge({
  descriptor,
  showIcon,
  className,
}: {
  descriptor: Descriptor;
  showIcon?: boolean;
  className?: string;
}) {
  const Icon = descriptor.pulse
    ? Loader2
    : descriptor.variant === 'success'
      ? CheckCircle2
      : descriptor.variant === 'destructive'
        ? XCircle
        : Clock;

  return (
    <Badge variant={descriptor.variant} className={cn('gap-1.5', className)}>
      {showIcon ? (
        <Icon className={cn('h-3 w-3', descriptor.pulse && 'animate-spin')} aria-hidden />
      ) : null}
      {descriptor.label}
    </Badge>
  );
}

export function OrderStatusBadge({
  status,
  showIcon = true,
  className,
}: {
  status: OrderStatus;
  showIcon?: boolean;
  className?: string;
}) {
  return <StatusBadge descriptor={ORDER_STATUS[status]} showIcon={showIcon} className={className} />;
}

export function PaymentStatusBadge({
  status,
  showIcon = true,
  className,
}: {
  status: PaymentStatus;
  showIcon?: boolean;
  className?: string;
}) {
  return (
    <StatusBadge descriptor={PAYMENT_STATUS[status]} showIcon={showIcon} className={className} />
  );
}

export function NotificationStatusBadge({
  status,
  showIcon = true,
  className,
}: {
  status: NotificationStatus;
  showIcon?: boolean;
  className?: string;
}) {
  return (
    <StatusBadge
      descriptor={NOTIFICATION_STATUS[status]}
      showIcon={showIcon}
      className={className}
    />
  );
}

export { ORDER_STATUS, PAYMENT_STATUS, NOTIFICATION_STATUS };

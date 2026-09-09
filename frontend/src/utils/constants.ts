/** Cross-feature constants that are not configuration. */

/** Order lifecycle, mirroring `OrderStatus` in Order Service. */
export const ORDER_STATUSES = [
  'CREATED',
  'INVENTORY_RESERVED',
  'PAID',
  'COMPLETED',
  'CANCELLED',
] as const;

/** Statuses at which the saga has finished and polling must stop. */
export const TERMINAL_ORDER_STATUSES = ['COMPLETED', 'CANCELLED'] as const;

export const PAYMENT_STATUSES = ['PENDING', 'COMPLETED', 'FAILED', 'REFUNDED'] as const;
export const PAYMENT_METHODS = ['CARD', 'IDEAL', 'PAYPAL', 'BANK_TRANSFER'] as const;
export const NOTIFICATION_STATUSES = ['PENDING', 'SENT', 'FAILED'] as const;

export const ROLES = { CUSTOMER: 'CUSTOMER', ADMIN: 'ADMIN' } as const;

/**
 * Fields the backend allows sorting by. Anything else is silently replaced with the default
 * server-side, so the UI only ever offers these.
 */
export const PRODUCT_SORT_FIELDS = ['name', 'price', 'createdAt', 'sku', 'category'] as const;
export const ORDER_SORT_FIELDS = [
  'createdAt',
  'updatedAt',
  'totalAmount',
  'status',
  'orderNumber',
] as const;

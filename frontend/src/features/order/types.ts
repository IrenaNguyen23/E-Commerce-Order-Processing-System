import type { PageParams } from '@/types/api';

/** DTOs for Order Service. Mirrors `com.commerceflow.orderservice.dto`. */

/**
 * The saga states, in order.
 *
 * `CREATED → INVENTORY_RESERVED → PAID → COMPLETED`, or `CANCELLED` from any non-terminal state.
 * The backend enforces the transitions; the UI only renders them.
 */
export type OrderStatus =
  | 'CREATED'
  | 'INVENTORY_RESERVED'
  | 'PAID'
  | 'COMPLETED'
  | 'CANCELLED';

/** Where the saga stopped, when it compensated. */
export type FailedStep = 'INVENTORY' | 'PAYMENT';

export interface OrderItem {
  productId: string;
  sku: string;
  /** Snapshotted at order time — a later catalogue change never rewrites what was agreed. */
  productName: string;
  /** Category at order time. The catalogue may have reorganised since. */
  productCategory: string | null;
  /** The image the customer saw — not the product's current one. */
  productImageUrl: string | null;
  quantity: number;
  /** Catalogue price at order time, before any discount. */
  listPrice: number;
  /** Per unit reduction, from a coupon or a promotion. */
  discountAmount: number;
  /** What was charged per unit: `listPrice - discountAmount`. */
  unitPrice: number;
  subtotal: number;
  /** What the tax was called where this went: VAT, BTW, GST. Snapshotted with the price. */
  taxName: string | null;
  /** The rate applied, as a fraction — `0.21` is 21%. Frozen at order time. */
  taxRate: number | null;
  /** Tax on this line, rounded here and summed into the order total. */
  taxAmount: number | null;
}

/** How a parcel travels. Mirrors `com.commerceflow.orderservice.pricing.ShippingMethod`. */
export type ShippingMethod = 'STANDARD' | 'EXPRESS' | 'PICKUP';

export const SHIPPING_METHOD_LABELS: Record<ShippingMethod, string> = {
  STANDARD: 'Standard delivery',
  EXPRESS: 'Express delivery',
  PICKUP: 'Collect in store',
};

/**
 * Where an order went, as the order itself recorded it.
 *
 * Returned alongside the formatted one-liner rather than instead of it: the string is what was
 * frozen at checkout and belongs on a label, these fields are what a form needs to prefill.
 * Re-parsing the one-liner works right up until an address contains a comma.
 */
export interface DeliveryDestination {
  recipientName: string | null;
  phone: string | null;
  line1: string | null;
  line2: string | null;
  city: string | null;
  region: string | null;
  postalCode: string | null;
  /** ISO-3166 alpha-2. This is what chose the tax rate and the delivery rate. */
  countryCode: string | null;
}

/** A range rather than a date, because a range is what was actually promised. */
export interface DeliverySummary {
  method: ShippingMethod | null;
  minDays: number | null;
  maxDays: number | null;
}

export interface Order {
  id: string;
  orderNumber: string;
  userId: string;
  userEmail: string;
  status: OrderStatus;
  /** The goods at list price, before anything came off. */
  subtotalAmount: number;
  /** Everything that came off, across the order. */
  discountTotal: number;
  /** Tax, summed from the per-line amounts. */
  taxTotal: number;
  /** What delivery cost. Zero when free or collected. */
  shippingAmount: number;
  /** What was charged: `subtotal - discount + tax + delivery`. */
  totalAmount: number;
  currency: string;
  /** One line, frozen at checkout. */
  shippingAddress: string;
  destination: DeliveryDestination | null;
  delivery: DeliverySummary | null;
  itemCount: number;
  items: OrderItem[];
  paymentId: string | null;
  /** The discount code that was used, if any. */
  couponCode: string | null;
  failedStep: FailedStep | null;
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt: string | null;
}

export interface CreateOrderItem {
  productId: string;
  quantity: number;
}

/**
 * A postal address, as the API takes it.
 *
 * Structured rather than one line of text, because the country code decides the tax rate and the
 * delivery charge — and a country parsed back out of free text is a country that will eventually
 * be parsed wrong, quietly, into a wrong total.
 */
export interface PostalAddress {
  recipientName: string;
  phone?: string;
  line1: string;
  line2?: string;
  city: string;
  region?: string;
  postalCode?: string;
  /** ISO-3166 alpha-2. */
  countryCode: string;
}

export interface CreateOrderPayload {
  items: CreateOrderItem[];
  shippingAddress: PostalAddress;
  shippingMethod?: ShippingMethod;
  /**
   * A code the customer entered. Claimed at checkout, so a code that passed the basket preview
   * can still be refused here — which is what a limited campaign means.
   */
  couponCode?: string;
  /** Client-generated. Replaying it returns the original order instead of placing a second one. */
  idempotencyKey?: string;
}

/** A priced delivery option. Mirrors `ShippingQuote`. */
export interface ShippingQuote {
  method: ShippingMethod;
  amount: number;
  currency: string;
  minDays: number;
  maxDays: number;
  free: boolean;
  description: string;
}

/**
 * What a discount code would be worth.
 *
 * Two figures, not one. A reduction on the goods lowers the tax with it; free delivery does not
 * touch the tax at all. A single "you save" number would make the checkout total disagree with
 * the basket page for reasons no customer could work out.
 */
export interface CouponPreview {
  code: string;
  type: 'PERCENTAGE' | 'FIXED_AMOUNT' | 'FREE_SHIPPING';
  description: string | null;
  goodsDiscount: number;
  shippingDiscount: number;
  currency: string;
}

export interface OrderSearchParams extends PageParams {
  status?: OrderStatus | '';
  /** ADMIN only; ignored for customers. */
  userId?: string;
}

// ---------------------------------------------------------------------------------------------
// Saga presentation
// ---------------------------------------------------------------------------------------------

export const TERMINAL_STATUSES: OrderStatus[] = ['COMPLETED', 'CANCELLED'];

export const isTerminal = (status: OrderStatus): boolean => TERMINAL_STATUSES.includes(status);

export const isInFlight = (status: OrderStatus): boolean => !isTerminal(status);

/** Ordinal position in the happy path, used to drive the progress timeline. */
export const STATUS_ORDER: Record<OrderStatus, number> = {
  CREATED: 0,
  INVENTORY_RESERVED: 1,
  PAID: 2,
  COMPLETED: 3,
  CANCELLED: -1,
};

export interface SagaStep {
  key: string;
  label: string;
  description: string;
}

/**
 * The four steps shown on the order timeline.
 *
 * Worded from the customer's point of view — "Stock reserved", not "inventory.reserved" — while
 * mapping one-to-one onto the backend states so the display can never disagree with the data.
 */
export const SAGA_STEPS: SagaStep[] = [
  { key: 'CREATED', label: 'Order placed', description: 'We have your order.' },
  {
    key: 'INVENTORY_RESERVED',
    label: 'Stock reserved',
    description: 'Your items are being held for you.',
  },
  { key: 'PAID', label: 'Payment taken', description: 'Your payment went through.' },
  { key: 'COMPLETED', label: 'Confirmed', description: 'Your order is confirmed.' },
];

export type StepState = 'done' | 'active' | 'pending' | 'failed';

/** Resolves the visual state of one timeline step for a given order status. */
export function stepStateFor(step: SagaStep, order: Order): StepState {
  const stepIndex = STATUS_ORDER[step.key as OrderStatus];
  const currentIndex = STATUS_ORDER[order.status];

  if (order.status === 'CANCELLED') {
    // Mark the step where it stopped as failed; everything before it did happen.
    const failedAt = order.failedStep === 'INVENTORY' ? 1 : 2;
    if (stepIndex < failedAt) return 'done';
    if (stepIndex === failedAt) return 'failed';
    return 'pending';
  }

  if (stepIndex < currentIndex) return 'done';
  if (stepIndex === currentIndex) return order.status === 'COMPLETED' ? 'done' : 'active';
  return 'pending';
}

/**
 * The delivery window an order was quoted, as a phrase.
 *
 * Returns `null` rather than inventing text when the order predates structured delivery or was
 * collected in store. A screen that must say something can fall back; one that would rather say
 * nothing can.
 */
export function deliveryWindow(order: Order): string | null {
  const delivery = order.delivery;
  if (!delivery || delivery.minDays == null || delivery.maxDays == null) return null;
  if (delivery.maxDays === 0) return null;
  return delivery.minDays === delivery.maxDays
    ? `${delivery.minDays} working days`
    : `${delivery.minDays}–${delivery.maxDays} working days`;
}

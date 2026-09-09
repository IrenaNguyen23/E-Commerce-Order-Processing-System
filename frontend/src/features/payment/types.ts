/** DTOs for Payment Service. Mirrors `com.commerceflow.paymentservice.dto`. */

export type PaymentStatus = 'PENDING' | 'COMPLETED' | 'FAILED' | 'REFUNDED';
export type PaymentMethod = 'CARD' | 'IDEAL' | 'PAYPAL' | 'BANK_TRANSFER';

export interface Payment {
  id: string;
  orderId: string;
  orderNumber: string | null;
  userId: string | null;
  amount: number;
  currency: string;
  status: PaymentStatus;
  method: PaymentMethod;
  /** Acquirer reference; present once the charge is approved. */
  transactionId: string | null;
  failureReason: string | null;
  /**
   * Authorises this browser to finish the charge.
   *
   * Present only while the payment is outstanding, and only for its owner. Null with the
   * simulated acquirer, which settles before anyone could use it — which is also why the payment
   * form simply never appears in the demo stack.
   */
  clientSecret: string | null;
  createdAt: string;
  processedAt: string | null;
}

export interface ProcessPaymentPayload {
  orderId: string;
  orderNumber?: string;
  amount: number;
  currency?: string;
  method?: PaymentMethod;
}

export const PAYMENT_METHOD_LABELS: Record<PaymentMethod, string> = {
  CARD: 'Card',
  IDEAL: 'iDEAL',
  PAYPAL: 'PayPal',
  BANK_TRANSFER: 'Bank transfer',
};

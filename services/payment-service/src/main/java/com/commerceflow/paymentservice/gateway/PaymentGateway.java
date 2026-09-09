package com.commerceflow.paymentservice.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The acquirer boundary.
 *
 * <p>Everything about talking to a real payment provider lives behind this interface, so changing
 * provider means adding an implementation, not touching the saga.
 *
 * <h2>Charging is not always synchronous</h2>
 *
 * <p>The simulated acquirer answers immediately. A real one frequently does not: a card may need
 * 3-D Secure, a bank transfer settles hours later, and even a plain card charge can come back
 * "processing". So {@link ChargeResult} has three outcomes rather than two, and
 * {@link Outcome#PENDING} means <em>ask again later, or wait to be told</em> — never "assume it
 * failed". Treating a pending charge as a decline is how an order gets cancelled after the
 * customer's money has left their account.
 */
public interface PaymentGateway {

    /**
     * Attempts to charge the customer.
     *
     * <p>Implementations must never throw for a declined payment — a decline is a business
     * outcome that has to reach the saga as {@code payment.failed}, not a technical error that
     * would be retried. Only genuine transport failures should propagate.
     *
     * <p><b>Implementations must be idempotent on {@code orderId}.</b> The orchestrator re-sends
     * an unanswered {@code PROCESS_PAYMENT}, so this method can be called more than once for the
     * same order, and the second call must return the first call's outcome rather than charging
     * again. Every serious provider offers an idempotency key for exactly this; use the order id.
     *
     * @param orderId  order being paid for, and the idempotency key at the acquirer
     * @param amount   amount to charge
     * @param currency ISO-4217 currency
     * @return the outcome, never {@code null}
     */
    ChargeResult charge(UUID orderId, BigDecimal amount, String currency);

    /**
     * Asks the acquirer what actually happened to a charge.
     *
     * <p>The escape hatch for a payment stuck in {@code PENDING}: a webhook that never arrived, a
     * process that died mid-flight. The acquirer is the system of record for whether money moved,
     * so this asks it rather than guessing.
     *
     * @param gatewayReference the provider's own identifier, stored when the charge was created
     * @return the current outcome, never {@code null}
     */
    ChargeResult reconcile(String gatewayReference);

    /**
     * Sends money back.
     *
     * <p>A refund is a second movement of money, not a charge undone: it has its own reference at
     * the acquirer, its own timing, and its own ways of failing. Treating it as a reversal is how
     * a customer gets refunded twice.
     *
     * <p><b>Implementations must be idempotent on {@code orderId}</b>, for the same reason
     * {@link #charge} is — the orchestrator re-sends an unanswered command, and this one moves
     * money in the direction that is hardest to get back.
     *
     * @param orderId          the order being refunded, and the idempotency key at the acquirer
     * @param gatewayReference the provider handle for the original charge
     * @param amount           how much to return
     * @param currency         ISO-4217 currency
     * @return the outcome, never {@code null}
     */
    RefundResult refund(UUID orderId, String gatewayReference, BigDecimal amount, String currency);

    /**
     * Outcome of a refund.
     *
     * @param success         whether the money is on its way back
     * @param refundReference the acquirer's reference for the reversal
     * @param failureReason   machine readable reason when it did not happen
     */
    record RefundResult(boolean success, String refundReference, String failureReason) {

        public static RefundResult succeeded(String refundReference) {
            return new RefundResult(true, refundReference, null);
        }

        public static RefundResult failed(String reason) {
            return new RefundResult(false, null, reason);
        }
    }

    /** Whether the charge is finished, and how. */
    enum Outcome {

        /** Money moved. */
        APPROVED,

        /** The acquirer refused. A business answer, not an error. */
        DECLINED,

        /**
         * Not finished. The result will arrive by webhook, or can be fetched later.
         *
         * <p>Not a failure. A saga that treats this as one cancels orders that were paid for.
         */
        PENDING
    }

    /**
     * Outcome of a charge attempt.
     *
     * @param outcome          what happened, or that nothing has yet
     * @param transactionId    acquirer reference for the movement of money, when approved
     * @param gatewayReference the provider's handle for this attempt, needed to reconcile later;
     *                         present whenever the provider created something, including pending
     * @param declineReason    machine readable reason when declined
     * @param clientSecret     what the browser needs to finish a pending payment, when the
     *                         provider issues one. Scoped to this attempt and designed to be sent
     *                         to the customer — it authorises paying <em>this</em> intent and
     *                         nothing else, which is why it is safe to hand over and useless to
     *                         anyone who intercepts it afterwards.
     */
    record ChargeResult(Outcome outcome, String transactionId, String gatewayReference,
                        String declineReason, String clientSecret) {

        public boolean isApproved() {
            return outcome == Outcome.APPROVED;
        }

        public boolean isDeclined() {
            return outcome == Outcome.DECLINED;
        }

        public boolean isPending() {
            return outcome == Outcome.PENDING;
        }

        public static ChargeResult approved(String transactionId) {
            return new ChargeResult(Outcome.APPROVED, transactionId, transactionId, null, null);
        }

        public static ChargeResult approved(String transactionId, String gatewayReference) {
            return new ChargeResult(Outcome.APPROVED, transactionId, gatewayReference, null, null);
        }

        public static ChargeResult declined(String reason) {
            return new ChargeResult(Outcome.DECLINED, null, null, reason, null);
        }

        public static ChargeResult declined(String reason, String gatewayReference) {
            return new ChargeResult(Outcome.DECLINED, null, gatewayReference, reason, null);
        }

        /** Nothing has been decided yet; {@code gatewayReference} is how to find out later. */
        public static ChargeResult pending(String gatewayReference) {
            return new ChargeResult(Outcome.PENDING, null, gatewayReference, null, null);
        }

        /** Pending, and the customer can finish it in the browser with this secret. */
        public static ChargeResult pending(String gatewayReference, String clientSecret) {
            return new ChargeResult(Outcome.PENDING, null, gatewayReference, null, clientSecret);
        }
    }
}

package com.commerceflow.paymentservice.gateway;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.commerceflow.common.money.Money;
import com.commerceflow.paymentservice.config.PaymentProperties;
import com.stripe.StripeClient;
import com.stripe.exception.IdempotencyException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe, behind the acquirer boundary.
 *
 * <h2>The idempotency key is the important line in this class</h2>
 *
 * <p>The orchestrator re-sends an unanswered {@code PROCESS_PAYMENT} up to three times. Against a
 * simulator that is free; against Stripe it would be three charges. Every create call therefore
 * carries {@code order-<orderId>} as its idempotency key, which makes Stripe return the
 * <em>original</em> PaymentIntent for a repeat rather than creating another. That one parameter is
 * what makes the retry policy safe to point at real money.
 *
 * <h2>Why a charge comes back PENDING</h2>
 *
 * <p>This creates a PaymentIntent but does not confirm it, because there is nothing to confirm it
 * with: the saga charges after the order is placed, with no customer at the keyboard and no saved
 * card on file. The intent is created, the money has not moved, and the outcome arrives later —
 * either when the customer completes payment in the browser, or over the webhook.
 *
 * <p>That is a genuine constraint of card payments, not a limitation of this class. See
 * {@code docs/architecture/payments.md} for what the checkout flow has to look like, and why the
 * simulated acquirer's charge-and-answer-immediately shape does not survive contact with a real
 * one.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "commerceflow.payment", name = "gateway", havingValue = "STRIPE")
public class StripePaymentGateway implements PaymentGateway {


    static final String REASON_CARD_DECLINED = "CARD_DECLINED";

    /**
     * The customer created an order and never paid for it.
     *
     * <p>Its own reason rather than a generic decline, because it is the one "failure" here that
     * is nobody's fault and needs no investigation — and because an operator reading a week of
     * cancelled orders needs to tell abandoned baskets apart from cards that were refused.
     */
    static final String REASON_ABANDONED = "PAYMENT_ABANDONED";

    /** Stripe's word for "created, but the customer has not entered anything". */
    static final String AWAITING_CUSTOMER = "requires_payment_method";
    static final String REASON_UNKNOWN_REFERENCE = "UNKNOWN_GATEWAY_REFERENCE";

    private final StripeClient stripe;
    private final PaymentProperties properties;

    public StripePaymentGateway(PaymentProperties properties) {
        this.properties = properties;

        String key = properties.getStripe().getSecretKey();
        if (key == null || key.isBlank()) {
            // Failing at start-up rather than on the first customer's order. A payment service
            // that boots without credentials only looks healthy until someone tries to buy
            // something.
            throw new IllegalStateException(
                    "commerceflow.payment.gateway is STRIPE but no secret key is configured. "
                            + "Set STRIPE_SECRET_KEY, or set the gateway back to SIMULATED.");
        }
        this.stripe = StripeClient.builder().setApiKey(key).build();
    }

    @Override
    public ChargeResult charge(UUID orderId, BigDecimal amount, String currency) {
        if (amount == null || amount.signum() <= 0) {
            return ChargeResult.declined("INVALID_AMOUNT");
        }

        String iso = (currency == null ? properties.getCurrency() : currency)
                .toLowerCase(Locale.ROOT);

        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(toMinorUnits(amount, iso))
                .setCurrency(iso)
                // Lets Stripe offer whatever the account has enabled — cards, wallets, local
                // methods — rather than hard-coding a list that goes stale.
                .setAutomaticPaymentMethods(
                        PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                                .setEnabled(true)
                                .build())
                // The order id travels to Stripe so a dispute or a refund can be traced back
                // without a lookup table, and so the dashboard is readable during an incident.
                .putMetadata("orderId", orderId.toString())
                .build();

        try {
            PaymentIntent intent = stripe.paymentIntents().create(params, requestOptions(orderId));
            return interpret(intent);

        } catch (IdempotencyException ex) {
            // The same key was reused with different parameters — the amount changed between two
            // attempts for one order. Charging would be wrong either way; this needs a human.
            log.error("Idempotency conflict at Stripe for order {}: the same order was submitted "
                    + "with different parameters", orderId, ex);
            return ChargeResult.declined("IDEMPOTENCY_CONFLICT");

        } catch (StripeException ex) {
            // Transport, not decision. Propagating lets the listener retry and, failing that,
            // dead-letter — rather than recording a decline for a charge nobody attempted.
            throw new GatewayUnavailableException(
                    "Stripe rejected the charge request for order " + orderId, ex);
        }
    }

    @Override
    public ChargeResult reconcile(String gatewayReference) {
        if (gatewayReference == null || gatewayReference.isBlank()) {
            return ChargeResult.declined(REASON_UNKNOWN_REFERENCE);
        }
        try {
            PaymentIntent intent = stripe.paymentIntents().retrieve(gatewayReference);

            if (isAbandoned(intent)) {
                // A customer who opened the payment screen and walked away. Cancelling the intent
                // makes that explicit at the acquirer as well as here, so the dashboard does not
                // fill up with charges that will never happen.
                cancelQuietly(intent);
                return ChargeResult.declined(REASON_ABANDONED, intent.getId());
            }
            return interpret(intent);

        } catch (StripeException ex) {
            throw new GatewayUnavailableException(
                    "Could not reconcile " + gatewayReference + " with Stripe", ex);
        }
    }

    @Override
    public RefundResult refund(UUID orderId, String gatewayReference, BigDecimal amount,
                               String currency) {
        if (gatewayReference == null || gatewayReference.isBlank()) {
            // Nothing at the acquirer to reverse. Reporting success would tell the customer their
            // money is coming back when nobody has sent it.
            return RefundResult.failed(REASON_UNKNOWN_REFERENCE);
        }

        String iso = (currency == null ? properties.getCurrency() : currency)
                .toLowerCase(java.util.Locale.ROOT);

        com.stripe.param.RefundCreateParams params = com.stripe.param.RefundCreateParams.builder()
                .setPaymentIntent(gatewayReference)
                .setAmount(toMinorUnits(amount, iso))
                .build();

        try {
            // Keyed on the order, and distinct from the charge key: the same order legitimately
            // has one charge and one refund, and sharing a key would make the second look like a
            // replay of the first.
            com.stripe.model.Refund refund = stripe.refunds().create(params,
                    com.stripe.net.RequestOptions.builder()
                            .setIdempotencyKey("refund-" + orderId)
                            .build());

            if ("failed".equals(refund.getStatus()) || "canceled".equals(refund.getStatus())) {
                return RefundResult.failed(
                        refund.getFailureReason() == null ? "REFUND_FAILED"
                                : refund.getFailureReason().toUpperCase(java.util.Locale.ROOT));
            }
            log.info("Refunded {} {} for order {} ({})", amount, iso, orderId, refund.getId());
            return RefundResult.succeeded(refund.getId());

        } catch (StripeException ex) {
            // Not a business answer. Letting it propagate keeps the command retryable rather than
            // recording a refusal the acquirer never gave.
            throw new GatewayUnavailableException(
                    "Stripe rejected the refund for order " + orderId, ex);
        }
    }

    /**
     * Whether this charge is an abandoned basket rather than one still in progress.
     *
     * <p>Only {@code requires_payment_method} counts. A customer part-way through 3-D Secure is
     * {@code requires_action} and must be left alone — cancelling those would fail payments that
     * were seconds from succeeding, which is a far worse error than waiting a little longer for a
     * basket nobody is coming back to.
     */
    private boolean isAbandoned(PaymentIntent intent) {
        if (!AWAITING_CUSTOMER.equals(intent.getStatus())) {
            return false;
        }
        java.time.Duration age = java.time.Duration.between(
                java.time.Instant.ofEpochSecond(intent.getCreated()), java.time.Instant.now());
        return age.compareTo(properties.getStripe().getAbandonAfter()) > 0;
    }

    /** Best effort. If Stripe will not cancel it, the local decision still stands. */
    private void cancelQuietly(PaymentIntent intent) {
        try {
            stripe.paymentIntents().cancel(intent.getId());
            log.info("Cancelled abandoned PaymentIntent {}", intent.getId());
        } catch (StripeException ex) {
            log.warn("Could not cancel abandoned PaymentIntent {}: {}", intent.getId(),
                    ex.getMessage());
        }
    }

    /**
     * Turns a PaymentIntent status into an outcome the saga understands.
     *
     * <p>The mapping that matters is the middle one: everything Stripe is still working on is
     * {@code PENDING}, never a decline. A saga that read "requires action" as failure would
     * cancel orders while the customer was still typing their 3-D Secure code.
     */
    private ChargeResult interpret(PaymentIntent intent) {
        String status = intent.getStatus();

        return switch (status) {
            case "succeeded" -> ChargeResult.approved(
                    intent.getLatestCharge() != null ? intent.getLatestCharge() : intent.getId(),
                    intent.getId());

            case "canceled" -> ChargeResult.declined(declineReason(intent), intent.getId());

            // requires_payment_method, requires_confirmation, requires_action, processing.
            // All of them mean "not yet", and the webhook will say which way it went.
            default -> {
                log.debug("PaymentIntent {} is {}", intent.getId(), status);
                yield ChargeResult.pending(intent.getId(), intent.getClientSecret());
            }
        };
    }

    private static String declineReason(PaymentIntent intent) {
        if (intent.getLastPaymentError() != null) {
            String code = intent.getLastPaymentError().getDeclineCode();
            if (code != null && !code.isBlank()) {
                return code.toUpperCase(Locale.ROOT);
            }
            String type = intent.getLastPaymentError().getCode();
            if (type != null && !type.isBlank()) {
                return type.toUpperCase(Locale.ROOT);
            }
        }
        return REASON_CARD_DECLINED;
    }

    /**
     * The idempotency key that makes the orchestrator's retry safe.
     *
     * <p>Derived from the order, not from the command: two <em>different</em> commands for the
     * same order are exactly the case that must not produce two charges, and a per-command key
     * would happily create one each.
     */
    private static com.stripe.net.RequestOptions requestOptions(UUID orderId) {
        return com.stripe.net.RequestOptions.builder()
                .setIdempotencyKey("order-" + orderId)
                .build();
    }

    /**
     * Converts to the minor units Stripe expects.
     *
     * @see Money for why this is not simply {@code × 100}
     */
    static long toMinorUnits(BigDecimal amount, String isoCurrency) {
        return Money.toMinorUnits(amount, isoCurrency);
    }

    /** Exposed so the webhook can read metadata the same way this class writes it. */
    static UUID orderIdFrom(Map<String, String> metadata) {
        String raw = metadata == null ? null : metadata.get("orderId");
        try {
            return raw == null ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

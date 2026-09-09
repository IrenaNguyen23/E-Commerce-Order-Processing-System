package com.commerceflow.paymentservice.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.commerceflow.paymentservice.config.PaymentProperties;
import com.commerceflow.paymentservice.service.PaymentService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;

/**
 * Where Stripe tells us how a charge went.
 *
 * <p>This endpoint marks orders paid, and it is reachable from the internet without a token. What
 * stands in for authentication is the signature: Stripe signs every delivery with a shared secret,
 * and a body that does not verify is rejected before it is parsed. Skipping that check — or
 * "temporarily" disabling it — would let anyone mark any order paid by posting JSON.
 *
 * <p>The raw body is taken as a {@code String} rather than a bound object on purpose. The
 * signature covers the exact bytes Stripe sent; letting Jackson deserialise first and
 * re-serialising to check would compare a reconstruction, which is not the same thing.
 *
 * <h2>Answering 200 to things we ignore</h2>
 *
 * <p>An unrecognised event type, or one for a payment this environment has never heard of, gets a
 * {@code 200}. Stripe retries anything else for days, and a staging account sharing a webhook with
 * production would otherwise generate an endless retry storm over events that are correctly being
 * ignored. Rejection is reserved for a body that fails its signature.
 */
@Slf4j
@RestController
@RequestMapping("/api/payments/webhook")
@ConditionalOnProperty(prefix = "commerceflow.payment", name = "gateway", havingValue = "STRIPE")
@Tag(name = "Payments", description = "Acquirer callbacks")
public class StripeWebhookController {

    private static final String SUCCEEDED = "payment_intent.succeeded";
    private static final String FAILED = "payment_intent.payment_failed";
    private static final String CANCELED = "payment_intent.canceled";

    private final PaymentService paymentService;
    private final String signingSecret;

    public StripeWebhookController(PaymentService paymentService, PaymentProperties properties) {
        this.paymentService = paymentService;
        this.signingSecret = properties.getStripe().getWebhookSecret();

        if (signingSecret == null || signingSecret.isBlank()) {
            // Refusing to start is the point. An unsigned webhook endpoint that marks orders paid
            // is worse than no webhook endpoint at all, and this is the last moment anyone will
            // notice it is missing.
            throw new IllegalStateException(
                    "commerceflow.payment.stripe.webhook-secret is required when the gateway is "
                            + "STRIPE. Without it this endpoint would accept anything posted to it.");
        }
    }

    @PostMapping
    @Operation(
            summary = "Stripe payment event",
            description = "Signed by Stripe and verified here. Not for manual use.")
    public ResponseEntity<String> receive(
            @RequestBody String payload,
            @RequestHeader(value = "Stripe-Signature", required = false) String signature) {

        Event event;
        try {
            event = Webhook.constructEvent(payload, signature, signingSecret);
        } catch (SignatureVerificationException ex) {
            log.warn("Rejected a webhook with an invalid signature: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid signature");
        } catch (RuntimeException ex) {
            log.warn("Rejected an unparseable webhook body", ex);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("invalid payload");
        }

        String type = event.getType();
        if (!SUCCEEDED.equals(type) && !FAILED.equals(type) && !CANCELED.equals(type)) {
            log.debug("Ignoring Stripe event {}", type);
            return ResponseEntity.ok("ignored");
        }

        PaymentIntent intent = paymentIntent(event);
        if (intent == null) {
            log.warn("Stripe event {} carried no readable PaymentIntent", event.getId());
            return ResponseEntity.ok("ignored");
        }

        boolean approved = SUCCEEDED.equals(type);
        String reason = approved ? null : declineReason(intent);

        boolean changed = paymentService.settleFromGateway(intent.getId(), approved, reason);

        log.info("Stripe {} for {} ({})", type, intent.getId(),
                changed ? "settled" : "no change");
        return ResponseEntity.ok("ok");
    }

    /**
     * Pulls the PaymentIntent out of the event envelope.
     *
     * <p>Stripe deserialises the object against the API version the <em>event</em> was created
     * with, which can differ from the SDK's. When it cannot, the object is absent rather than
     * wrong — so this returns null and the caller ignores the event instead of acting on a
     * half-read one.
     */
    private static PaymentIntent paymentIntent(Event event) {
        StripeObject object = event.getDataObjectDeserializer().getObject().orElse(null);
        return object instanceof PaymentIntent intent ? intent : null;
    }

    private static String declineReason(PaymentIntent intent) {
        if (intent.getLastPaymentError() != null) {
            String code = intent.getLastPaymentError().getDeclineCode();
            if (code != null && !code.isBlank()) {
                return code.toUpperCase(java.util.Locale.ROOT);
            }
        }
        return "CARD_DECLINED";
    }
}

package com.commerceflow.paymentservice.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Payment Service tuning knobs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.payment")
public class PaymentProperties {

    /** Which {@code PaymentGateway} implementation to use. */
    private String gateway = "SIMULATED";

    /** Artificial latency of the simulated acquirer, to make the async flow visible in a demo. */
    private long processingDelayMs = 0L;

    /**
     * Simulated acquirer: any charge at or above this amount is declined.
     *
     * <p>Deterministic on purpose — it makes the compensation branch of the saga reproducible in
     * a test or a demo without having to inject a failure.
     */
    private BigDecimal declineThreshold = new BigDecimal("10000.00");

    /** Simulated acquirer: fraction of charges declined at random, between 0.0 and 1.0. */
    private double failureRate = 0.0d;

    /** Currency assumed when an order does not carry one. */
    private String currency = "EUR";

    @org.springframework.boot.context.properties.NestedConfigurationProperty
    private Stripe stripe = new Stripe();

    /** Stripe credentials. Only read when {@code gateway} is {@code STRIPE}. */
    @Getter
    @Setter
    public static class Stripe {

        /** Secret key. Never a publishable one — that belongs in the browser, not here. */
        private String secretKey;

        /**
         * How long a customer has to actually pay before the order is given up on.
         *
         * <p>An abandoned basket is the ordinary case in this flow, not an incident: the order
         * exists, stock is held, and nobody ever entered a card. Without this the saga would park
         * every one of them for an operator, which turns a normal shopping behaviour into a page.
         *
         * <p>Must be comfortably shorter than the orchestrator's total retry budget
         * ({@code SAGA_STEP_TIMEOUT} × {@code SAGA_MAX_ATTEMPTS}), or the saga parks before this
         * ever gets a chance to fire. See {@code docs/architecture/payments.md}.
         */
        private java.time.Duration abandonAfter = java.time.Duration.ofMinutes(10);

        /**
         * Webhook signing secret.
         *
         * <p>Without it the webhook endpoint would accept anything posted to it, and the endpoint
         * is what marks orders paid. It is not optional in any deployment that faces the internet.
         */
        private String webhookSecret;
    }
}

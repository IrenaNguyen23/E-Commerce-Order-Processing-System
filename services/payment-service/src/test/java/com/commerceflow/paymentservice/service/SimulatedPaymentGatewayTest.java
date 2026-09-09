package com.commerceflow.paymentservice.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.commerceflow.paymentservice.config.PaymentProperties;
import com.commerceflow.paymentservice.gateway.PaymentGateway;
import com.commerceflow.paymentservice.gateway.SimulatedPaymentGateway;

/**
 * The simulated acquirer has to be deterministic by default: the compensation branch of the saga
 * is triggered on demand in demos and integration tests, and a random default would make every
 * other test flaky.
 */
class SimulatedPaymentGatewayTest {

    private PaymentProperties properties;
    private SimulatedPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        properties = new PaymentProperties();
        properties.setDeclineThreshold(new BigDecimal("10000.00"));
        properties.setFailureRate(0.0d);
        properties.setProcessingDelayMs(0L);
        gateway = new SimulatedPaymentGateway(properties);
    }

    @Test
    @DisplayName("an ordinary amount is approved with an acquirer reference")
    void approvesOrdinaryAmount() {
        PaymentGateway.ChargeResult result =
                gateway.charge(UUID.randomUUID(), new BigDecimal("1899.00"), "EUR");

        assertThat(result.isApproved()).isTrue();
        assertThat(result.transactionId()).startsWith("SIM-");
        assertThat(result.declineReason()).isNull();
    }

    @Test
    @DisplayName("an amount at or above the threshold is declined deterministically")
    void declinesAtThreshold() {
        assertThat(gateway.charge(UUID.randomUUID(), new BigDecimal("10000.00"), "EUR").isApproved())
                .isFalse();
        assertThat(gateway.charge(UUID.randomUUID(), new BigDecimal("25000.00"), "EUR")
                .declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(gateway.charge(UUID.randomUUID(), new BigDecimal("9999.99"), "EUR").isApproved())
                .isTrue();
    }

    @Test
    @DisplayName("a non-positive amount is declined rather than charged")
    void declinesNonPositiveAmount() {
        assertThat(gateway.charge(UUID.randomUUID(), BigDecimal.ZERO, "EUR").declineReason())
                .isEqualTo("INVALID_AMOUNT");
        assertThat(gateway.charge(UUID.randomUUID(), new BigDecimal("-1"), "EUR").isApproved())
                .isFalse();
        assertThat(gateway.charge(UUID.randomUUID(), null, "EUR").isApproved()).isFalse();
    }

    @Test
    @DisplayName("a failure rate of 1.0 declines every charge")
    void honoursFailureRate() {
        properties.setFailureRate(1.0d);

        PaymentGateway.ChargeResult result =
                gateway.charge(UUID.randomUUID(), new BigDecimal("10.00"), "EUR");

        assertThat(result.isApproved()).isFalse();
        assertThat(result.declineReason()).isEqualTo("CARD_DECLINED");
    }
}

package com.commerceflow.paymentservice.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Converting an amount to the minor units an acquirer expects.
 *
 * <p>Worth its own test file because the failure mode is silent and expensive: getting it wrong
 * for a zero-decimal currency charges the customer a hundred times the price, and nothing in the
 * type system objects. A Vietnamese shop selling in dong is the most likely place for it to bite.
 */
class StripeAmountConversionTest {

    @Test
    @DisplayName("a two-decimal currency is multiplied by a hundred")
    void euroBecomesCents() {
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("18.99"), "eur"))
                .isEqualTo(1899L);
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("1899.00"), "eur"))
                .isEqualTo(189_900L);
    }

    @Test
    @DisplayName("a zero-decimal currency is not multiplied at all")
    void dongStaysWhole() {
        // 500,000 VND is 500000, not 50000000. The second would be a hundredfold overcharge and
        // the customer would be the one to discover it.
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("500000"), "vnd"))
                .isEqualTo(500_000L);
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("1500"), "jpy"))
                .isEqualTo(1_500L);
    }

    @Test
    @DisplayName("the currency code is matched regardless of case")
    void caseDoesNotMatter() {
        // Stripe wants lower case, the catalogue stores upper case. A comparison that missed
        // would silently take the two-decimal branch for dong.
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("500000"), "VND"))
                .isEqualTo(StripePaymentGateway.toMinorUnits(new BigDecimal("500000"), "vnd"));
    }

    @Test
    @DisplayName("fractions of a minor unit are rounded, not truncated")
    void roundsHalfUp() {
        // A price that arrives with more precision than the currency has must not silently lose
        // money in either direction.
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("10.005"), "eur"))
                .isEqualTo(1001L);
        assertThat(StripePaymentGateway.toMinorUnits(new BigDecimal("10.004"), "eur"))
                .isEqualTo(1000L);
    }
}

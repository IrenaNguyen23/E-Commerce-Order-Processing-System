package com.commerceflow.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Currency scale, which is the difference between charging someone 250 000 dong and charging them
 * 25 000 000.
 */
class MoneyTest {

    @Test
    @DisplayName("a zero-decimal currency is not multiplied by a hundred")
    void zeroDecimalCurrenciesAreNotScaledUp() {
        // The classic e-commerce overcharge. A blanket x100 turns 250,000 VND into 25,000,000 —
        // a hundredfold, and it looks like a large order rather than a bug.
        assertThat(Money.toMinorUnits(new BigDecimal("250000"), "VND")).isEqualTo(250_000L);
        assertThat(Money.toMinorUnits(new BigDecimal("1500"), "JPY")).isEqualTo(1_500L);
    }

    @Test
    @DisplayName("an ordinary currency is")
    void twoDecimalCurrenciesAreScaled() {
        assertThat(Money.toMinorUnits(new BigDecimal("19.99"), "EUR")).isEqualTo(1_999L);
        assertThat(Money.toMinorUnits(new BigDecimal("19.99"), "usd")).isEqualTo(1_999L);
    }

    @Test
    @DisplayName("three-decimal currencies exist and are not two-decimal")
    void threeDecimalCurrencies() {
        assertThat(Money.scaleOf("KWD")).isEqualTo(3);
        assertThat(Money.toMinorUnits(new BigDecimal("1.234"), "KWD")).isEqualTo(1_234L);
    }

    @Test
    @DisplayName("rounding a dong amount leaves nothing after the point")
    void roundingRespectsTheCurrency() {
        // A fraction of a dong cannot be charged, stored on an invoice or reconciled. Rounding
        // it here is what stops a gateway rounding it somewhere else, differently.
        assertThat(Money.round(new BigDecimal("250000.49"), "VND"))
                .isEqualByComparingTo(new BigDecimal("250000"));
        assertThat(Money.round(new BigDecimal("19.995"), "EUR"))
                .isEqualByComparingTo(new BigDecimal("20.00"));
    }

    @Test
    @DisplayName("half goes up, the way a person doing the sum on paper would")
    void roundingIsHalfUp() {
        // An invoice a customer cannot reproduce by hand is an invoice they will call about.
        assertThat(Money.round(new BigDecimal("0.005"), "EUR"))
                .isEqualByComparingTo(new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("a percentage is rounded once, where it is worked out")
    void percentageRoundsAtTheSource() {
        assertThat(Money.percentageOf(new BigDecimal("80.00"), new BigDecimal("0.2100"), "EUR"))
                .isEqualByComparingTo(new BigDecimal("16.80"));
        assertThat(Money.percentageOf(new BigDecimal("3.33"), new BigDecimal("0.2100"), "EUR"))
                .isEqualByComparingTo(new BigDecimal("0.70"));
    }

    @Test
    @DisplayName("an unknown currency is treated as two-decimal rather than rejected")
    void unknownCurrencyDefaultsToTwo() {
        // Two decimals is right for the overwhelming majority. Being wrong for a currency that
        // is not in either list is better than a checkout that fails on it.
        assertThat(Money.scaleOf("ZZZ")).isEqualTo(2);
        assertThat(Money.scaleOf(null)).isEqualTo(2);
    }

    @Test
    @DisplayName("minor units survive a round trip")
    void minorUnitsRoundTrip() {
        assertThat(Money.fromMinorUnits(1_999L, "EUR"))
                .isEqualByComparingTo(new BigDecimal("19.99"));
        assertThat(Money.fromMinorUnits(250_000L, "VND"))
                .isEqualByComparingTo(new BigDecimal("250000"));
    }

    @Test
    @DisplayName("an amount too large to charge fails loudly rather than truncating")
    void absurdAmountsThrow() {
        // A figure that does not fit in a long is a corrupted number, not a large purchase.
        // Truncating it would send a plausible-looking charge to a gateway.
        assertThatThrownBy(() -> Money.toMinorUnits(new BigDecimal("1e30"), "EUR"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("zero is expressed at the currency's own scale")
    void zeroCarriesTheScale() {
        assertThat(Money.zero("EUR").scale()).isEqualTo(2);
        assertThat(Money.zero("VND").scale()).isZero();
    }
}

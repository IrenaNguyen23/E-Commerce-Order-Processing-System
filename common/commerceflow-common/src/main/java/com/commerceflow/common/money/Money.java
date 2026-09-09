package com.commerceflow.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Set;

/**
 * How much a currency can be divided into, and therefore where every amount gets rounded.
 *
 * <h2>Why this is shared rather than local to whoever needs it</h2>
 *
 * <p>Two places have to agree about this or money goes missing. The order works out a tax figure
 * and stores it; the payment service converts a total into the minor units a gateway wants. If the
 * first rounds VND to two decimal places and the second rounds it to none, the order says
 * 250 000.49 and the customer is charged 250 000 — and the difference turns up as a reconciliation
 * mismatch weeks later, not as an error.
 *
 * <p>So the scale of a currency is decided once, here, and both sides ask.
 *
 * <h2>Rounding rule</h2>
 *
 * <p>{@link RoundingMode#HALF_UP} everywhere, because it is what a person doing the same sum on
 * paper does, and an invoice a customer cannot reproduce by hand is an invoice they will call
 * about. It is applied <b>per line</b> and the lines are then added — never the other way round.
 * Rounding a total and apportioning it backwards is what leaves a cent unaccounted for.
 */
public final class Money {

    /**
     * Currencies with no minor unit at all.
     *
     * <p>A blanket two-decimal assumption is the classic e-commerce bug: it makes
     * {@code 250000 VND} into {@code 25000000}, a hundredfold overcharge, or silently invents
     * fractions of a dong that no payment system can represent.
     */
    private static final Set<String> ZERO_DECIMAL = Set.of(
            "BIF", "CLP", "DJF", "GNF", "JPY", "KMF", "KRW", "MGA", "PYG",
            "RWF", "UGX", "VND", "VUV", "XAF", "XOF", "XPF");

    /** Currencies with three decimal places rather than two. */
    private static final Set<String> THREE_DECIMAL = Set.of(
            "BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND");

    private Money() {
    }

    /** Decimal places this currency actually has: 0, 2 or 3. */
    public static int scaleOf(String currency) {
        String iso = normalise(currency);
        if (ZERO_DECIMAL.contains(iso)) {
            return 0;
        }
        return THREE_DECIMAL.contains(iso) ? 3 : 2;
    }

    /** Rounds an amount to something the currency can actually express. */
    public static BigDecimal round(BigDecimal amount, String currency) {
        return amount == null ? null : amount.setScale(scaleOf(currency), RoundingMode.HALF_UP);
    }

    /** Zero, at this currency's scale — so a stored zero looks like every other stored amount. */
    public static BigDecimal zero(String currency) {
        return BigDecimal.ZERO.setScale(scaleOf(currency));
    }

    /**
     * Converts to the integer minor units payment gateways transact in.
     *
     * @throws ArithmeticException if the amount does not fit in a {@code long}, which is a
     *     corrupted figure rather than a large purchase and should never be quietly truncated
     */
    public static long toMinorUnits(BigDecimal amount, String currency) {
        return round(amount, currency)
                .movePointRight(scaleOf(currency))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** Turns minor units back into an amount, for reading a gateway's reply. */
    public static BigDecimal fromMinorUnits(long minorUnits, String currency) {
        return BigDecimal.valueOf(minorUnits, scaleOf(currency));
    }

    /**
     * Applies a rate to a base and rounds the result.
     *
     * <p>The one place tax and percentage discounts are worked out, so both round identically.
     */
    public static BigDecimal percentageOf(BigDecimal base, BigDecimal rate, String currency) {
        if (base == null || rate == null) {
            return zero(currency);
        }
        return round(base.multiply(rate), currency);
    }

    private static String normalise(String currency) {
        return currency == null ? "" : currency.trim().toUpperCase(Locale.ROOT);
    }
}

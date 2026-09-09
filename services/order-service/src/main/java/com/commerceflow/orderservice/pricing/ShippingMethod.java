package com.commerceflow.orderservice.pricing;

/**
 * How the parcel travels.
 *
 * <p>Deliberately short. Every entry here is a promise about a delivery date that somebody has to
 * keep, and a checkout that offers six speeds is a checkout where five of them are guesses. Rates
 * and delivery windows for each are rows in {@code shipping_rates}, per country.
 */
public enum ShippingMethod {

    /** The default. Cheapest, and the one quoted when the customer expresses no preference. */
    STANDARD,

    /** Faster, and priced accordingly. */
    EXPRESS,

    /**
     * The customer collects it themselves.
     *
     * <p>Always free and always same-day, so it does not need a rate row — which is exactly why it
     * is an enum constant rather than another line on the rate card: a "free" rate row that
     * somebody later edits to a non-zero amount would start charging people for walking to a shop.
     */
    PICKUP;

    /** Pickup never costs anything, whatever the rate card says. */
    public boolean isChargeable() {
        return this != PICKUP;
    }

    public static ShippingMethod orDefault(ShippingMethod method) {
        return method == null ? STANDARD : method;
    }
}

package com.commerceflow.orderservice.promotion;

/**
 * What a coupon actually does.
 *
 * <p>Three kinds, and they are genuinely different rather than three settings of one number:
 * a percentage scales with the basket, a fixed amount does not, and free delivery touches a
 * different line of the invoice entirely.
 */
public enum DiscountType {

    /** A share of the goods: {@code value} is a fraction, so 0.1000 is 10% off. */
    PERCENTAGE,

    /**
     * A flat sum off the goods, in the coupon's own currency.
     *
     * <p>The one that has to be spread across the lines, because tax is charged per line. See
     * {@code DiscountAllocator}.
     */
    FIXED_AMOUNT,

    /**
     * Delivery costs nothing, whatever the rate card says.
     *
     * <p>Takes the shipping charge to zero rather than discounting the goods, so it never changes
     * the taxable base — which is the point: a free-delivery coupon that reduced the goods would
     * quietly reduce the tax as well.
     */
    FREE_SHIPPING;

    /** Whether this kind reduces the goods, and therefore the amount tax is charged on. */
    public boolean reducesGoods() {
        return this != FREE_SHIPPING;
    }
}

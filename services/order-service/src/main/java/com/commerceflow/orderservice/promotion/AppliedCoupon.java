package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;

/**
 * The outcome of actually claiming a code, returned to the checkout so it can record what
 * happened on the order.
 *
 * <p>{@code goodsDiscount} is what was <em>attributed to the lines</em>, which can be a little
 * under the code's face value when the basket is smaller than the discount. That figure, not the
 * coupon's value, is what the order records — an order has to be able to explain its own
 * arithmetic without anything else being consulted.
 */
public record AppliedCoupon(String code, DiscountType type, BigDecimal goodsDiscount) {

    /** Whether this code takes the delivery charge to zero. */
    public boolean freesShipping() {
        return type == DiscountType.FREE_SHIPPING;
    }
}

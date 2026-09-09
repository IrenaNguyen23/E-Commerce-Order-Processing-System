package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What a code would be worth, shown on the basket page before anything is committed.
 *
 * <p>Goods and delivery are separate figures rather than one total, because they behave
 * differently: a reduction on the goods lowers the tax with it, and free delivery does not touch
 * the tax at all. A single "you save" number would hide that, and the checkout total would then
 * disagree with the basket page for reasons no customer could work out.
 */
@Schema(description = "What a discount code would be worth on this basket")
public record CouponPreview(
        String code,
        DiscountType type,
        String description,
        @Schema(description = "Off the goods; this also reduces the tax charged")
        BigDecimal goodsDiscount,
        @Schema(description = "Off delivery; does not affect tax")
        BigDecimal shippingDiscount,
        String currency) {

    /** Everything the code is worth, for a client that only wants one figure. */
    public BigDecimal totalSaving() {
        return goodsDiscount.add(shippingDiscount);
    }
}

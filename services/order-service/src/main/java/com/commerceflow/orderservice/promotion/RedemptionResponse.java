package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One use of a code.
 *
 * <p>{@code discountAmount} is what it was actually worth on that order, which can be less than the
 * code's face value when the basket was smaller than the discount. That figure, not the coupon's
 * value, is what the campaign cost.
 */
@Schema(description = "One use of a discount code")
public record RedemptionResponse(
        UUID orderId,
        UUID userId,
        @Schema(description = "What it was worth on this order, in that order's currency")
        BigDecimal discountAmount,
        String currency,
        Instant redeemedAt) {

    static RedemptionResponse of(CouponRedemption redemption) {
        return new RedemptionResponse(redemption.getOrderId(), redemption.getUserId(),
                redemption.getDiscountAmount(), redemption.getCurrency(),
                redemption.getRedeemedAt());
    }
}

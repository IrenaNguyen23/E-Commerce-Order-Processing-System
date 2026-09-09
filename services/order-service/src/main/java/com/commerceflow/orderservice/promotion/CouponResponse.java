package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** A discount code, as the back office sees it. */
@Schema(description = "A discount code")
public record CouponResponse(
        UUID id,
        String code,
        String description,
        DiscountType type,
        @Schema(description = "A fraction for PERCENTAGE; an amount for FIXED_AMOUNT")
        BigDecimal value,
        String currency,
        BigDecimal minimumBasket,
        Integer maxRedemptions,
        int redemptionCount,
        Integer perCustomerLimit,
        Instant validFrom,
        Instant validUntil,
        boolean active,
        @Schema(description = "Whether it would be accepted right now, ignoring any basket. "
                + "Computed rather than stored: a campaign expires by the clock, not by a sweep.")
        boolean live,
        @Schema(description = "Uses left, or null when the campaign is unlimited")
        Integer remaining,
        Instant createdAt,
        Instant updatedAt) {

    static CouponResponse of(Coupon coupon) {
        Integer remaining = coupon.getMaxRedemptions() == null
                ? null
                : Math.max(0, coupon.getMaxRedemptions() - coupon.getRedemptionCount());

        return new CouponResponse(coupon.getId(), coupon.getCode(), coupon.getDescription(),
                coupon.getType(), coupon.getValue(), coupon.getCurrency(),
                coupon.getMinimumBasket(), coupon.getMaxRedemptions(),
                coupon.getRedemptionCount(), coupon.getPerCustomerLimit(), coupon.getValidFrom(),
                coupon.getValidUntil(), coupon.isActive(),
                coupon.isLiveAt(Instant.now()) && coupon.hasAllowanceLeft(),
                remaining, coupon.getCreatedAt(), coupon.getUpdatedAt());
    }
}

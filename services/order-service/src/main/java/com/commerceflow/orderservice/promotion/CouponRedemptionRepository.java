package com.commerceflow.orderservice.promotion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    /** How many times this customer has already used this code. */
    long countByCouponIdAndUserId(UUID couponId, UUID userId);

    /** The redemption to undo when an order is cancelled, if there was one. */
    Optional<CouponRedemption> findByOrderId(UUID orderId);

    boolean existsByCouponIdAndOrderId(UUID couponId, UUID orderId);

    /** Every use of one code, most recent first. */
    java.util.List<CouponRedemption> findByCouponIdOrderByRedeemedAtDesc(UUID couponId);

    long countByCouponId(UUID couponId);
}

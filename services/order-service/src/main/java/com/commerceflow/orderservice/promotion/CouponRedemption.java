package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One use of one coupon, on one order.
 *
 * <h2>Why the ledger exists rather than just a counter</h2>
 *
 * <p>A counter on the coupon says how many times it was used. It cannot answer "has this customer
 * already used it", which is the per-customer limit, and it cannot be undone accurately when an
 * order is cancelled. This ledger answers both, and the unique constraint on
 * {@code (coupon_id, order_id)} is what makes claiming a coupon idempotent: a retried checkout
 * cannot spend the same allowance twice.
 *
 * <h2>Cancelling gives the coupon back</h2>
 *
 * <p>A redemption is released when the order is cancelled. Keeping it would mean a customer who
 * cancelled an order — or whose payment was declined — has silently burned their code, which is
 * indistinguishable from the code not working and is exactly the kind of thing support cannot
 * explain.
 */
@Entity
@Table(name = "coupon_redemptions", indexes = {
        @Index(name = "idx_coupon_redemptions_coupon_user", columnList = "coupon_id, user_id"),
        @Index(name = "idx_coupon_redemptions_order", columnList = "order_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponRedemption {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "coupon_id", nullable = false)
    private UUID couponId;

    /** Kept alongside the id so a report does not need a join to say which code was used. */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    /** What it was actually worth on this order, in the order's currency. */
    @Column(name = "discount_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "redeemed_at", nullable = false)
    private Instant redeemedAt;
}

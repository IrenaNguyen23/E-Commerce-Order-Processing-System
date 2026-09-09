package com.commerceflow.orderservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.commerceflow.orderservice.pricing.ShippingMethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The CQRS read model: one flat, pre-joined row per order.
 *
 * <p>Kept in the same database as the write model so the projection commits in the same
 * transaction as the state change it reflects — the read side is therefore never stale, while
 * still being a single-row, index-only read with the line items already denormalised into JSON.
 *
 * <p>Separating it from {@link Order} is what lets the customer-facing "my orders" list scale
 * independently of the write model: no joins, no lazy collections, no optimistic-locking column,
 * and the projection can be rebuilt from the aggregate at any time.
 */
@Entity
@Table(name = "order_read_model", indexes = {
        @Index(name = "idx_order_view_user_created", columnList = "user_id, created_at"),
        @Index(name = "idx_order_view_status", columnList = "status"),
        @Index(name = "idx_order_view_number", columnList = "order_number")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderView {

    @Id
    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrderStatus status;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    /** Sum of the lines at list price, before any discount. */
    @Column(name = "subtotal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotalAmount;

    /** Total reduction across the order. */
    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal;

    /** Tax, summed from the per-line amounts on the aggregate. */
    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "shipping_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal shippingAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    // The destination is projected as flat columns rather than being re-parsed out of the
    // formatted line, which works until an address contains a comma.

    @Column(name = "recipient_name", length = 150)
    private String recipientName;

    @Column(name = "shipping_phone", length = 32)
    private String shippingPhone;

    @Column(name = "shipping_line1", length = 200)
    private String shippingLine1;

    @Column(name = "shipping_line2", length = 200)
    private String shippingLine2;

    @Column(name = "shipping_city", length = 100)
    private String shippingCity;

    @Column(name = "shipping_region", length = 100)
    private String shippingRegion;

    @Column(name = "shipping_postal_code", length = 20)
    private String shippingPostalCode;

    @Column(name = "shipping_country", length = 2)
    private String shippingCountry;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", length = 16)
    private ShippingMethod shippingMethod;

    @Column(name = "delivery_min_days")
    private Integer deliveryMinDays;

    @Column(name = "delivery_max_days")
    private Integer deliveryMaxDays;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    /** Denormalised line items, so a read never has to join. */
    @Column(name = "items_json", nullable = false, columnDefinition = "text")
    private String itemsJson;

    @Column(name = "payment_id")
    private UUID paymentId;

    /** The code that was used, as text. See {@link Order#getCouponCode()}. */
    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "failed_step", length = 32)
    private String failedStep;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}

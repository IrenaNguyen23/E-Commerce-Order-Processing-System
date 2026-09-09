package com.commerceflow.orderservice.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.commerceflow.common.exception.ConflictException;
import com.commerceflow.orderservice.pricing.ShippingMethod;
import com.commerceflow.common.exception.ErrorCode;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The order aggregate — the system of record for what the customer bought.
 *
 * <p>State transitions go through {@link #transitionTo(OrderStatus)} rather than a plain setter,
 * so an out-of-order or duplicate saga event cannot silently move a completed order backwards.
 */
@Entity
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_user", columnList = "user_id"),
        @Index(name = "idx_orders_status", columnList = "status"),
        @Index(name = "idx_orders_created", columnList = "created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Human readable reference, e.g. {@code CF-20260826-000123}. */
    @Column(name = "order_number", nullable = false, unique = true, length = 32)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private OrderStatus status;

    /** Sum of the lines at list price, before any discount. */
    @Column(name = "subtotal_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotalAmount;

    /** Total reduction across the order. Structurally zero until promotions exist. */
    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal;

    /**
     * Tax across the order: the sum of the per-line amounts, already rounded.
     *
     * <p>Added up from the lines rather than computed here, so the figure on the invoice is the
     * sum of the figures beside it. See {@code TaxCalculator}.
     */
    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal taxTotal = BigDecimal.ZERO;

    /** What delivery cost. Quoted tax-inclusive; see {@code TaxCalculator} for why. */
    @Column(name = "shipping_amount", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal shippingAmount = BigDecimal.ZERO;

    /**
     * What the customer was charged:
     * {@code subtotalAmount - discountTotal + taxTotal + shippingAmount}.
     *
     * <p>This is the number the payment step charges and the number a refund returns. Every other
     * amount on the order exists to explain it.
     */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /**
     * The address, formatted for display and for a label.
     *
     * <p>Derived from the structured fields below at checkout and then frozen. Kept as a column
     * rather than rebuilt on read so that a change to the formatter never alters what an old order
     * says was on its parcel.
     */
    @Column(name = "shipping_address", nullable = false, length = 500)
    private String shippingAddress;

    // ---- the destination, structured -----------------------------------------------------
    //
    // Copied from whatever the customer submitted, never a foreign key into the address book:
    // correcting a typo in a saved address must not rewrite where last year's parcels went, and
    // deleting one must not orphan the orders that used it.

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

    /**
     * ISO-3166 alpha-2. Not decoration: it chose the tax rate and the shipping rate.
     *
     * <p>Kept on the order for the same reason the rate itself is — so an invoice can be
     * explained years later without asking where the customer lives now.
     */
    @Column(name = "shipping_country", length = 2)
    private String shippingCountry;

    /** How it travels, and the window quoted when the customer agreed to pay. */
    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", length = 16)
    private ShippingMethod shippingMethod;

    @Column(name = "delivery_min_days")
    private Integer deliveryMinDays;

    @Column(name = "delivery_max_days")
    private Integer deliveryMaxDays;

    /**
     * Optional client supplied key. Unique per customer, so replaying a create request over a
     * flaky connection returns the original order instead of charging the customer twice.
     */
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "payment_id")
    private UUID paymentId;

    /**
     * The discount code that was used, if any.
     *
     * <p>The code as text, not a foreign key into {@code coupons}. A campaign can be renamed,
     * deactivated or deleted, and an order still has to be able to say what came off it and why —
     * the same reason the product name is copied rather than referenced.
     */
    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    /** Populated when the saga compensates, and surfaced to the customer. */
    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /** Saga step that failed, e.g. {@code INVENTORY} or {@code PAYMENT}. */
    @Column(name = "failed_step", length = 32)
    private String failedStep;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void addItem(OrderItem item) {
        item.setOrder(this);
        this.items.add(item);
    }

    /**
     * Applies a saga outcome.
     *
     * @return {@code true} when the state actually changed, {@code false} when the transition was
     *     a no-op because the order is already in (or past) that state — which is what makes a
     *     redelivered event harmless
     * @throws ConflictException when the transition is illegal, e.g. completing a cancelled order
     */
    public boolean transitionTo(OrderStatus next) {
        if (this.status == next) {
            return false;
        }
        if (!this.status.canTransitionTo(next)) {
            throw new ConflictException(ErrorCode.ORDER_NOT_MODIFIABLE,
                    "Order " + orderNumber + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = Instant.now();
        if (next == OrderStatus.COMPLETED) {
            this.completedAt = this.updatedAt;
        }
        return true;
    }

    /**
     * Cancels an order that had already completed.
     *
     * <p>Deliberately outside {@link OrderStatus#canTransitionTo}, and that separation is the
     * point. The state machine refuses to move a terminal order because it is guarding against a
     * <em>late saga reply</em> — a stray {@code payment.failed} arriving after the order finished.
     * A cancellation is not a late reply. It is a new decision, taken now, by a person.
     *
     * <p>Keeping the guard strict and making this the one named exception means the exception is
     * greppable, rather than the guard being loosened until it protects nothing.
     */
    public void cancelAfterCompletion(String step, String reason) {
        recordFailure(step, reason);
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
    }

    /** Records why the saga compensated; kept separate from the transition itself. */
    public void recordFailure(String step, String reason) {
        this.failedStep = step;
        this.failureReason = reason;
    }

    /** What the goods come to after discounts, before tax and delivery. */
    public BigDecimal goodsAfterDiscount() {
        return items.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Freezes the money on this order from the lines that make it up.
     *
     * <p>Five numbers that have to agree, computed together in one place so they cannot drift
     * apart:
     *
     * <pre>
     *   subtotalAmount = sum(listPrice x quantity)         what the goods listed at
     *   discountTotal  = sum(discountAmount x quantity)    what came off
     *   taxTotal       = sum(line tax)                     already rounded, per line
     *   shippingAmount                                     set by the shipping quote
     *   totalAmount    = subtotal - discount + tax + shipping
     * </pre>
     *
     * <p>{@code taxTotal} is added up from the lines rather than derived from the total, so the
     * invoice adds up as printed. {@code shippingAmount} is not recomputed here — it comes from a
     * quote made against the destination, and re-deriving it would need context the aggregate does
     * not have.
     */
    public void recalculateTotals() {
        this.subtotalAmount = items.stream()
                .map(item -> item.getListPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.discountTotal = items.stream()
                .map(item -> item.getDiscountAmount()
                        .multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        this.taxTotal = items.stream()
                .map(OrderItem::taxOrZero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (this.shippingAmount == null) {
            this.shippingAmount = BigDecimal.ZERO;
        }

        this.totalAmount = subtotalAmount
                .subtract(discountTotal)
                .add(taxTotal)
                .add(shippingAmount);
    }

    /**
     * Removes the customer from this order, keeping the order.
     *
     * <p>Called when somebody asks to be forgotten. An order is a financial record: the total, the
     * tax and the payment behind it all have to still add up next year, so the row survives and
     * only the person inside it goes.
     *
     * <p>{@code shippingCountry} deliberately stays. It is what chose the tax rate, so an invoice
     * that no longer says which country it was for cannot be explained — and a country on its own
     * identifies nobody. The postcode does go: combined with almost anything else it is close to
     * an identifier, and no report needs it.
     */
    public void anonymise(String placeholderEmail, String placeholderName) {
        this.userEmail = placeholderEmail;
        this.recipientName = placeholderName;
        this.shippingAddress = placeholderName;
        this.shippingLine1 = null;
        this.shippingLine2 = null;
        this.shippingRegion = null;
        this.shippingPostalCode = null;
        this.shippingPhone = null;
        this.updatedAt = Instant.now();
    }

    public int itemCount() {
        return items.stream().mapToInt(OrderItem::getQuantity).sum();
    }
}

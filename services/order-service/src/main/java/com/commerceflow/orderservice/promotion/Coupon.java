package com.commerceflow.orderservice.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A discount code.
 *
 * <h2>The redemption counter is not a field this class increments</h2>
 *
 * <p>{@link #redemptionCount} exists here so the number can be read and shown, but nothing in the
 * application ever does {@code coupon.setRedemptionCount(count + 1)}. A read-modify-write on a
 * counter that limits how many times something may happen is a race with a business consequence:
 * a code meant for the first hundred customers goes to a hundred and eleven, and no error is
 * raised anywhere. The increment is a single conditional {@code UPDATE} in
 * {@code CouponRepository#claim}, and the row count it returns is the answer to "was there one
 * left".
 *
 * <p>The optimistic-locking {@code version} column does not help here either. It turns a lost
 * update into a failed transaction, which is right for an aggregate somebody is editing and wrong
 * for a counter under contention — the moment a code is popular, checkouts start failing.
 *
 * <h2>Expiry is a window, not a flag</h2>
 *
 * <p>{@link #validFrom} and {@link #validUntil} rather than an "expired" boolean somebody has to
 * remember to set. A campaign that starts at midnight starts at midnight without anyone being
 * awake, and a code that has run out stops working without a sweep having run.
 */
@Entity
@Table(name = "coupons", indexes = {
        @Index(name = "idx_coupons_code", columnList = "code", unique = true),
        @Index(name = "idx_coupons_active", columnList = "active")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Coupon {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * What the customer types. Stored upper case; matched upper case.
     *
     * <p>Case-insensitive because "welcome10" and "WELCOME10" are the same code to everybody
     * except a database, and a customer who typed it the way it was printed on a poster should
     * not have to guess.
     */
    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "description", length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private DiscountType type;

    /** A fraction for {@code PERCENTAGE}, an amount for {@code FIXED_AMOUNT}, ignored otherwise. */
    @Column(name = "value", nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    /**
     * The currency a {@code FIXED_AMOUNT} coupon is denominated in.
     *
     * <p>Checked against the basket rather than converted. "10 off" in a currency nobody is
     * shopping in has no defensible meaning, and inventing an exchange rate at checkout is worse
     * than refusing the code.
     */
    @Column(name = "currency", length = 3)
    private String currency;

    /** Basket value, before the discount, below which the code does not apply. */
    @Column(name = "minimum_basket", precision = 19, scale = 4)
    private BigDecimal minimumBasket;

    /** Null means an unlimited campaign. */
    @Column(name = "max_redemptions")
    private Integer maxRedemptions;

    /** Read for display. Incremented only by the conditional update; see the class comment. */
    @Column(name = "redemption_count", nullable = false)
    @Builder.Default
    private int redemptionCount = 0;

    /**
     * How many times one customer may use it. Null means as often as they like.
     *
     * <p>Separate from {@link #maxRedemptions} because they stop different things: one caps the
     * campaign, the other stops one person taking all of it.
     */
    @Column(name = "per_customer_limit")
    private Integer perCustomerLimit;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_until")
    private Instant validUntil;

    /** An off switch for a campaign that has to stop now, ahead of its window. */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Whether the campaign is open right now, ignoring anything about a particular basket. */
    public boolean isLiveAt(Instant when) {
        return active
                && (validFrom == null || !when.isBefore(validFrom))
                && (validUntil == null || when.isBefore(validUntil));
    }

    /** Whether the campaign has any redemptions left, as last read. */
    public boolean hasAllowanceLeft() {
        return maxRedemptions == null || redemptionCount < maxRedemptions;
    }

    /** Codes are normalised on the way in, so the unique index means what it appears to mean. */
    public static String normalise(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }
}

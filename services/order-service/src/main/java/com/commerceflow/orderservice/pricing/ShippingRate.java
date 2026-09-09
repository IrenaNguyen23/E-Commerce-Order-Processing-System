package com.commerceflow.orderservice.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What it costs to send a parcel to one country by one method.
 *
 * <h2>Priced from the basket, not from a carrier</h2>
 *
 * <p>This is a rate card: a base charge, a small amount per item, and a threshold above which
 * delivery is free. It is not a carrier integration, and the difference is worth being explicit
 * about — a real quote depends on weight, dimensions and the carrier's own API, and none of those
 * exist here. Products carry no weight, so any figure derived from one would be fiction.
 *
 * <p>What this does give is a number that is correct by its own rules, visible to the customer
 * before they pay, and stored on the order. Swapping it for a carrier quote later means replacing
 * {@code ShippingQuoteService}, not touching the order or the saga.
 *
 * <h2>Free delivery is a threshold, not a coupon</h2>
 *
 * <p>{@link #freeAbove} compares against the basket <em>after</em> discounts. Comparing before
 * would mean a customer whose coupon takes them under the threshold still gets free delivery,
 * which is the kind of rule that is discovered and shared rather than reported.
 */
@Entity
@Table(name = "shipping_rates", indexes = {
        @Index(name = "idx_shipping_rates_lookup", columnList = "country_code, method")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingRate {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * ISO-3166 alpha-2, or {@code *} for the rest of the world.
     *
     * <p>A wildcard row rather than an error for unlisted countries: refusing to quote is refusing
     * the order, and a shop that cannot price delivery to Chile should say what it costs to ship
     * to Chile, not fail at the last step of checkout.
     */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "method", nullable = false, length = 16)
    private ShippingMethod method;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    /** Charged once per order. */
    @Column(name = "base_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal baseAmount;

    /** Charged per unit, for the parcels a large order turns into. */
    @Column(name = "per_item_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal perItemAmount;

    /** Basket value, after discounts, above which delivery costs nothing. Null means never. */
    @Column(name = "free_above", precision = 19, scale = 4)
    private BigDecimal freeAbove;

    /** Quoted to the customer as a range, because that is what it honestly is. */
    @Column(name = "min_days", nullable = false)
    private int minDays;

    @Column(name = "max_days", nullable = false)
    private int maxDays;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** True for the catch-all row that prices everywhere without a specific rate. */
    public boolean isWildcard() {
        return "*".equals(countryCode);
    }
}

package com.commerceflow.inventoryservice.entity;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A place stock physically is.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A single global stock number is a lie the moment a shop has two buildings. It says twelve
 * units are available; six are in Amsterdam and six are in Milan, and an order for eight from a
 * customer in Amsterdam either ships in two parcels from two countries or cannot ship at all —
 * and nothing in the system knew that before somebody in a warehouse found out.
 *
 * <p>Once stock is located, the interesting questions become answerable: which building can fill
 * this order, is it worth splitting, and what does a customer in Milan actually get offered.
 *
 * <h2>Priority is a merchandising decision, not a distance</h2>
 *
 * <p>{@link #priority} orders warehouses when more than one could fill a line. Lower goes first.
 * It is an explicit number rather than a computed distance because "which building should this
 * come from" depends on carrier contracts, staffing and what a shop is trying to clear — none of
 * which a coordinate can express.
 *
 * <p>{@link #countryCode} is used before priority: shipping from inside the destination country
 * avoids a customs form and usually a day. That is a rule general enough to encode; anything more
 * specific belongs in the priority number.
 */
@Entity
@Table(name = "warehouses", indexes = {
        @Index(name = "idx_warehouses_code", columnList = "code", unique = true),
        @Index(name = "idx_warehouses_active", columnList = "active, priority")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Warehouse {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Short stable identifier, upper case: {@code AMS}, {@code MIL}. Appears on paperwork. */
    @Column(name = "code", nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    /** ISO-3166 alpha-2. Checked before priority when choosing where to ship from. */
    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    @Column(name = "city", length = 100)
    private String city;

    /** Lower goes first when more than one warehouse could fill a line. */
    @Column(name = "priority", nullable = false)
    @Builder.Default
    private int priority = 100;

    /**
     * Whether new orders may be allocated from here.
     *
     * <p>Turning it off stops <em>new</em> allocations and leaves existing reservations alone.
     * That distinction matters during a stock take or a move: the building stops taking work
     * without abandoning the orders it has already promised to fill.
     */
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

    /** Codes are normalised on the way in, so the unique index means what it appears to mean. */
    public static String normaliseCode(String code) {
        return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** True when this warehouse is in the country an order is going to. */
    public boolean isIn(String destinationCountry) {
        return destinationCountry != null && destinationCountry.equalsIgnoreCase(countryCode);
    }
}

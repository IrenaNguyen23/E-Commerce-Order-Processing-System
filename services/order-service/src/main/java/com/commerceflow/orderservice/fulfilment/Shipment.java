package com.commerceflow.orderservice.fulfilment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.commerceflow.common.exception.ConflictException;
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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A parcel, and where it has got to.
 *
 * <h2>Every change is kept, not overwritten</h2>
 *
 * <p>{@link #status} is the current state and {@link #events} is how it got there. That is not
 * belt-and-braces: "when did this ship?" and "why does the carrier say they tried on Tuesday when
 * we marked it delivered on Monday?" are the two questions support actually receives, and neither
 * can be answered by a column that only holds the latest value.
 *
 * <h2>The tracking number is not a link</h2>
 *
 * <p>{@link #trackingNumber} is stored as text and {@link #trackingUrl} is stored separately rather
 * than being built from a carrier name at render time. Carriers change their URL formats, and a
 * link constructed from a pattern in the code silently breaks for every historical shipment when
 * they do.
 */
@Entity
@Table(name = "shipments", indexes = {
        @Index(name = "idx_shipments_order", columnList = "order_id"),
        @Index(name = "idx_shipments_status", columnList = "status"),
        @Index(name = "idx_shipments_tracking", columnList = "tracking_number")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Shipment {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    /** Denormalised so a warehouse list does not join to show what everything is called. */
    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private ShipmentStatus status = ShipmentStatus.PENDING;

    /** Whoever is carrying it: PostNL, DHL, or the customer themselves. */
    @Column(name = "carrier", length = 100)
    private String carrier;

    @Column(name = "tracking_number", length = 100)
    private String trackingNumber;

    /** Stored, not derived from the carrier name. See the class comment. */
    @Column(name = "tracking_url", length = 500)
    private String trackingUrl;

    /**
     * Where it is going, copied from the order.
     *
     * <p>A copy, because the order's own address is already a copy of what the customer submitted,
     * and because a shipping label has to keep saying what it said when it was printed.
     */
    @Column(name = "destination", nullable = false, length = 500)
    private String destination;

    /** What the customer was told at checkout, so a late parcel is visibly late. */
    @Column(name = "promised_by")
    private Instant promisedBy;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    @OrderBy("recordedAt ASC")
    @Builder.Default
    private List<ShipmentEvent> events = new ArrayList<>();

    /**
     * Moves the parcel on, recording how and why.
     *
     * @throws ConflictException when the move makes no sense — a cancelled shipment coming back to
     *     life, a delivered one going back to being picked. Corrections that genuinely happen to
     *     parcels are allowed; see {@link ShipmentStatus#allowedNext()}.
     */
    public ShipmentEvent moveTo(ShipmentStatus next, String note, String location, UUID actor) {
        if (!status.canMoveTo(next)) {
            throw new ConflictException(ErrorCode.CONFLICT,
                    "A shipment cannot go from " + status + " to " + next);
        }

        Instant now = Instant.now();
        this.status = next;
        this.updatedAt = now;

        // Set once, on the first transition into each. A parcel re-marked as dispatched after a
        // failed delivery attempt has not been dispatched twice.
        if (next == ShipmentStatus.DISPATCHED && dispatchedAt == null) {
            this.dispatchedAt = now;
        }
        if (next == ShipmentStatus.DELIVERED && deliveredAt == null) {
            this.deliveredAt = now;
        }

        ShipmentEvent event = ShipmentEvent.builder()
                .id(UUID.randomUUID())
                .shipment(this)
                .status(next)
                .note(note)
                .location(location)
                .recordedBy(actor)
                .recordedAt(now)
                .build();
        events.add(event);
        return event;
    }

    /** True when the customer was promised it by now and it has not arrived. */
    public boolean isLate() {
        return promisedBy != null && !status.isFinished() && Instant.now().isAfter(promisedBy);
    }
}

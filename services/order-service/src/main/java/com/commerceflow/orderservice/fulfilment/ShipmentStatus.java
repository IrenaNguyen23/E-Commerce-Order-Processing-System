package com.commerceflow.orderservice.fulfilment;

import java.util.Set;

/**
 * Where a parcel is.
 *
 * <h2>Why this is not part of {@code OrderStatus}</h2>
 *
 * <p>The obvious move is to add {@code SHIPPED} and {@code DELIVERED} to the order's own status,
 * and it is wrong for a specific reason: {@code OrderStatus} is the saga's state machine. Every
 * transition in it is guarded so that a late or duplicated saga reply cannot move an order
 * backwards, and the guard is what makes redelivery safe.
 *
 * <p>Fulfilment is a different lifecycle with a different clock. It starts after the saga has
 * finished, it is driven by people and carriers rather than by messages, and it can legitimately
 * go backwards — a parcel marked delivered that turns out to have been signed for by the wrong
 * building. Folding the two together would mean either loosening the saga's guard, which is what
 * it exists for, or refusing corrections that warehouse staff genuinely need to make.
 *
 * <p>So an order is {@code COMPLETED} and its shipment is {@code IN_TRANSIT}. Two facts, both
 * true, neither pretending to be the other.
 */
public enum ShipmentStatus {

    /** Created, nothing has happened yet. */
    PENDING,

    /** Being picked and packed in the warehouse. */
    PICKING,

    /** Handed to the carrier. This is the point a tracking number becomes useful. */
    DISPATCHED,

    /** The carrier has it and is moving it. */
    IN_TRANSIT,

    /** The carrier tried and could not — nobody home, wrong address, refused. */
    ATTEMPTED,

    /** It arrived. */
    DELIVERED,

    /** It came back. */
    RETURNED,

    /** Cancelled before it went anywhere. */
    CANCELLED;

    /**
     * What may follow this state.
     *
     * <p>Looser than the saga's transitions on purpose. {@code DELIVERED} can go to
     * {@code RETURNED}, and {@code ATTEMPTED} can go back to {@code IN_TRANSIT}, because both of
     * those things happen to real parcels. What is refused is only what is nonsense — a cancelled
     * shipment coming back to life, or a delivered one being marked as still being picked.
     */
    public Set<ShipmentStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(PICKING, DISPATCHED, CANCELLED);
            case PICKING -> Set.of(DISPATCHED, CANCELLED);
            case DISPATCHED -> Set.of(IN_TRANSIT, ATTEMPTED, DELIVERED, RETURNED);
            case IN_TRANSIT -> Set.of(ATTEMPTED, DELIVERED, RETURNED);
            case ATTEMPTED -> Set.of(IN_TRANSIT, DELIVERED, RETURNED);
            case DELIVERED -> Set.of(RETURNED);
            case RETURNED, CANCELLED -> Set.of();
        };
    }

    public boolean canMoveTo(ShipmentStatus next) {
        return this == next || allowedNext().contains(next);
    }

    /** Whether this state is worth emailing a customer about. */
    public boolean isWorthTelling() {
        return this == DISPATCHED || this == ATTEMPTED || this == DELIVERED || this == RETURNED;
    }

    public boolean isFinished() {
        return this == DELIVERED || this == RETURNED || this == CANCELLED;
    }
}

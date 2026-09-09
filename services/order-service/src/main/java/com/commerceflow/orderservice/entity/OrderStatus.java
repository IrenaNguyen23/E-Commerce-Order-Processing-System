package com.commerceflow.orderservice.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of an order, mirroring the saga in {@code event-flow.md}.
 *
 * <pre>
 * CREATED -> INVENTORY_RESERVED -> PAID -> COMPLETED
 *    |               |              |
 *    +---------------+--------------+--> CANCELLED
 * </pre>
 */
public enum OrderStatus {

    /** Persisted and {@code order.created} published; waiting for Inventory. */
    CREATED,

    /** Stock is held; waiting for Payment. */
    INVENTORY_RESERVED,

    /** The customer has been charged; the order is about to complete. */
    PAID,

    /** Terminal success. */
    COMPLETED,

    /** Terminal failure; every completed step has been compensated. */
    CANCELLED;

    private static final Set<OrderStatus> TERMINAL = EnumSet.of(COMPLETED, CANCELLED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** @return whether {@code next} is a legal transition from this state. */
    public boolean canTransitionTo(OrderStatus next) {
        if (isTerminal()) {
            return false;
        }
        return switch (this) {
            case CREATED -> next == INVENTORY_RESERVED || next == CANCELLED;
            case INVENTORY_RESERVED -> next == PAID || next == CANCELLED;
            case PAID -> next == COMPLETED || next == CANCELLED;
            default -> false;
        };
    }
}

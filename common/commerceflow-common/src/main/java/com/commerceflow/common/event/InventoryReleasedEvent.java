package com.commerceflow.common.event;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code inventory.released} — a previously held reservation was compensated
 * and the stock is available again.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class InventoryReleasedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private UUID reservationId;

    /** What triggered the compensation, e.g. {@code PAYMENT_FAILED} or {@code ORDER_CANCELLED}. */
    private String reason;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

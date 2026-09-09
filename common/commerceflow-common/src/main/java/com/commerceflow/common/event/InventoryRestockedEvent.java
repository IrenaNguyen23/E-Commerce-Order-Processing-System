package com.commerceflow.common.event;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Published on {@code inventory.restocked} — sold goods are back on the shelf. */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class InventoryRestockedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private UUID reservationId;

    /** How many units went back. Zero when there was nothing to return. */
    private int unitsRestocked;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

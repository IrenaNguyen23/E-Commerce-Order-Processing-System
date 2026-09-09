package com.commerceflow.common.event;

import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code inventory.confirmed} — a hold became a permanent deduction.
 *
 * <p>The reply to {@link ConfirmInventoryCommand}. It exists so the final forward step of the
 * saga is acknowledged like every other one: a step with no reply is a step the orchestrator
 * cannot tell apart from a participant that is down.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class InventoryConfirmedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private UUID reservationId;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

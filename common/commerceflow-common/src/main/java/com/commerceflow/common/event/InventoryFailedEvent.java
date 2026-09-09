package com.commerceflow.common.event;

import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code inventory.failed} — stock could not be reserved.
 *
 * <p>Consumed by Order Service, which compensates by cancelling the order.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class InventoryFailedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID userId;
    private String userEmail;

    /** Machine readable reason, e.g. {@code OUT_OF_STOCK} or {@code PRODUCT_NOT_FOUND}. */
    private String reason;

    /** SKUs that could not be satisfied; empty when the failure was not stock related. */
    private List<String> unavailableSkus;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

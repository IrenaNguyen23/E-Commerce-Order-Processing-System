package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code inventory.reserved} — stock is held for the order.
 *
 * <p>Consumed by Payment Service (which charges the customer) and by Order Service (which moves
 * the order to {@code INVENTORY_RESERVED}). The payable amount and the customer identity are
 * carried on the event so Payment Service never has to call back into Order Service.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class InventoryReservedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID reservationId;
    private UUID userId;
    private String userEmail;
    private BigDecimal totalAmount;
    private String currency;
    private List<OrderLineItem> items;

    /** Always {@code RESERVED}; kept for contract compatibility with {@code contracts/events.md}. */
    @lombok.Builder.Default
    private String status = "RESERVED";

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

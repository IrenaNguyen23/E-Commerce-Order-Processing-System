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
 * Published on {@code order.created} — the first step of the saga.
 *
 * <p>Consumed by Inventory Service, which attempts to reserve stock for every line.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class OrderCreatedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID userId;
    private String userEmail;
    private BigDecimal totalAmount;
    private String currency;
    private List<OrderLineItem> items;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code order.cancelled} — terminal failure state of the saga.
 *
 * <p>Consumed by Notification Service and, defensively, by Inventory Service so that any
 * still-open reservation is released even if the {@code payment.failed} compensation was lost.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class OrderCancelledEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID userId;
    private String userEmail;
    private BigDecimal totalAmount;
    private String currency;

    /** Saga step that failed, e.g. {@code INVENTORY} or {@code PAYMENT}. */
    private String failedStep;

    /** Human readable failure reason, propagated to the customer notification. */
    private String reason;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

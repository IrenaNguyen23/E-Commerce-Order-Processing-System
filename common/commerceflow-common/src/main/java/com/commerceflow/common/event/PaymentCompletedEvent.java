package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code payment.completed} — the customer has been charged.
 *
 * <p>Consumed by Order Service, which completes the order.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class PaymentCompletedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID paymentId;
    private UUID userId;
    private String userEmail;
    private BigDecimal amount;
    private String currency;

    /** Reference returned by the payment provider. */
    private String transactionId;

    /** Always {@code SUCCESS}; kept for contract compatibility with {@code contracts/events.md}. */
    @lombok.Builder.Default
    private String status = "SUCCESS";

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

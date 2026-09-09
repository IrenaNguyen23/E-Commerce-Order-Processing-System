package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code payment.failed} — the charge was declined.
 *
 * <p>Consumed by Inventory Service (releases the reservation) and by Order Service
 * (cancels the order). Both compensations are idempotent.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class PaymentFailedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID paymentId;
    private UUID reservationId;
    private UUID userId;
    private String userEmail;
    private BigDecimal amount;
    private String currency;

    /** Machine readable reason, e.g. {@code INSUFFICIENT_FUNDS} or {@code CARD_DECLINED}. */
    private String reason;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

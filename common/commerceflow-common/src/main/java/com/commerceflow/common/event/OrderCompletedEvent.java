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
 * Published on {@code order.completed} — terminal success state of the saga.
 *
 * <p>A domain event rather than a saga reply: it announces that something happened and does not
 * expect an answer. The saga does not know or care who listens, which is what lets a consumer be
 * added without touching the flow.
 *
 * <p>Consumed by Notification Service, to send the confirmation, and by Inventory Service, which
 * records who has bought what so that a review can be marked as coming from a real purchase.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class OrderCompletedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private String orderNumber;
    private UUID userId;
    private String userEmail;
    private UUID paymentId;
    private BigDecimal totalAmount;
    private String currency;

    /**
     * What was bought.
     *
     * <p>Added so that a consumer can know which products an order contained without calling back
     * into Order Service for them. A request-response there would put a synchronous dependency in
     * the middle of an asynchronous announcement, and make the publisher's availability the
     * subscriber's problem.
     */
    private List<OrderLineItem> items;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

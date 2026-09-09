package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Give a customer their money back for goods they have sent back.
 *
 * <h2>Why this is not {@link RefundPaymentCommand}</h2>
 *
 * <p>That command is saga compensation: an order was cancelled before it completed, so the whole
 * payment is reversed and the payment row becomes {@code REFUNDED}. It is deliberately all or
 * nothing, and answering a repeat with "already refunded" is what stops a re-sent compensation
 * sending the money twice.
 *
 * <p>A return is a different movement. It happens after the order completed, it is frequently
 * <em>partial</em> — two of three items came back — and the same order can produce several of
 * them weeks apart. Routing returns through the compensation path would mean the first partial
 * refund marked the payment fully refunded, and the second one succeeded without sending
 * anything. Same word, different operation.
 *
 * <h2>Not saga traffic</h2>
 *
 * <p>A {@link DomainEvent} rather than a {@link SagaCommand}, because nothing orchestrates this.
 * There is no compensation for a refund that fails: the goods are already back on the shelf and
 * the customer is already owed. A failure ends up in front of a person, which is the only thing
 * that can actually resolve it.
 *
 * <p>Answered on {@code return.refunded}, success or not.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class RefundReturnCommand extends DomainEvent {

    private static final long serialVersionUID = 1L;

    /**
     * The return this refund settles.
     *
     * <p>Also the idempotency key at the payment side. A return refunds once however many times
     * this command is delivered, and that guarantee is anchored to something the business
     * recognises rather than to a message id.
     */
    private UUID returnId;

    private UUID orderId;

    private String orderNumber;

    /** What the returned lines came to, tax included. Never more than the order was charged. */
    private BigDecimal amount;

    private String currency;

    /** Shown on the customer's statement narrative where the acquirer supports one. */
    private String reason;

    /**
     * Partitioned by order, so two refunds for the same order are handled in the order they were
     * raised. Refunds for different orders are independent and may go in parallel.
     */
    @Override
    public String partitionKey() {
        return orderId == null ? null : orderId.toString();
    }
}

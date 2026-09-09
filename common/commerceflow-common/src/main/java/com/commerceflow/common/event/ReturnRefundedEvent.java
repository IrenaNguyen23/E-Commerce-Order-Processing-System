package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * What happened when a return refund was attempted.
 *
 * <h2>One reply for both outcomes</h2>
 *
 * <p>Success and failure arrive on the same topic, distinguished by {@link #success}, rather than
 * there being a separate failure topic. A refund that fails is not a different kind of event — it
 * is the same event with a bad outcome, and the return has to hear about it either way so it can
 * stop saying "refund in progress".
 *
 * <p>The alternative, silence on failure, is the worst possible design here: the return would sit
 * forever in a pending state that means both "working on it" and "it failed and nobody noticed".
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class ReturnRefundedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID returnId;

    private UUID orderId;

    /**
     * What was actually sent back, which can be less than was asked for.
     *
     * <p>The payment side caps a refund at what is left unrefunded on the payment. Asking for more
     * than the order was charged is a bug somewhere upstream, and it is refused there rather than
     * discovered on a bank statement.
     */
    private BigDecimal amount;

    private String currency;

    /** The acquirer's reference. What reconciliation matches against, so it is stored. */
    private String refundReference;

    private boolean success;

    /** Why not, in the provider's words. Read by a person, so it is not translated or shortened. */
    private String failureReason;

    @Override
    public String partitionKey() {
        return orderId == null ? null : orderId.toString();
    }
}

package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Published on {@code payment.refunded} — the money has been sent back, or could not be.
 *
 * <p>Unlike the other replies this one carries a {@code success} flag rather than having a
 * separate failure topic. A refund that fails is not a branch the saga can compensate its way out
 * of: the goods are already coming back and the order is already cancelled. All that is left is
 * to record it and put it in front of a human, which is one outcome of one step rather than two
 * different paths.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class PaymentRefundedEvent extends DomainEvent {

    private static final long serialVersionUID = 1L;

    private UUID orderId;
    private UUID paymentId;
    private BigDecimal amount;
    private String currency;

    /** The acquirer's reference for the reversal; absent when it did not happen. */
    private String refundReference;

    /** False when the acquirer refused. The customer is owed money and nobody has sent it. */
    private boolean success;

    /** Machine readable reason when the refund failed. */
    private String failureReason;

    @Override
    public String partitionKey() {
        return orderId != null ? orderId.toString() : null;
    }
}

package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Compensation for step 2, sent on {@code payment.commands}: give the money back.
 *
 * <p>Answered with {@code payment.refunded}. Issued only for an order whose payment actually
 * completed — the orchestrator knows that from its own saga row rather than asking, which is what
 * keeps a cancellation from trying to refund a charge that never happened.
 *
 * <p>A refund is not a decline reversed. It is a second movement of money, with its own reference
 * at the acquirer, its own timing, and its own ways of failing. Treating it as an undo is how a
 * customer ends up refunded twice.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class RefundPaymentCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    /** The payment to reverse, learned from the reply that completed it. */
    private UUID paymentId;

    /**
     * How much to give back.
     *
     * <p>Carried explicitly rather than implied as "all of it", so a partial refund needs no new
     * command the day returns of single lines are supported.
     */
    private BigDecimal amount;

    private String currency;

    /** Why, recorded against the payment and shown to the customer. */
    private String reason;
}

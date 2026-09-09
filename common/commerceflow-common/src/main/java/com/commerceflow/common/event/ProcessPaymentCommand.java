package com.commerceflow.common.event;

import java.math.BigDecimal;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Saga step 2, sent on {@code payment.commands}: charge the customer.
 *
 * <p>Answered with {@code payment.completed} or {@code payment.failed}. Sent only once stock is
 * actually held, so money never moves for goods that cannot be shipped.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class ProcessPaymentCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    /** The reservation this payment pays for; carried through to the compensation step. */
    private UUID reservationId;

    private UUID userId;
    private String userEmail;
    private BigDecimal amount;
    private String currency;
}

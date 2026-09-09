package com.commerceflow.common.event;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Saga step 3, sent on {@code inventory.commands}: turn the hold into a permanent deduction.
 *
 * <p>Answered with {@code inventory.confirmed}. Sent only after the customer has been charged,
 * so stock is never written off for an order that was not paid for.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class ConfirmInventoryCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;
}

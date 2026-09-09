package com.commerceflow.common.event;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Compensation for step 1, sent on {@code inventory.commands}: give the held stock back.
 *
 * <p>Answered with {@code inventory.released}. Issued only by the orchestrator, and only for a
 * saga whose reserve step actually succeeded — which is the difference between orchestrated
 * compensation and hoping every participant works out for itself whether it has anything to undo.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class ReleaseInventoryCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    /** Why the stock is going back, e.g. {@code PAYMENT_FAILED}. Recorded on the reservation. */
    private String reason;
}

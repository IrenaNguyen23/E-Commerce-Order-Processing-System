package com.commerceflow.common.event;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Compensation for step 3, sent on {@code inventory.commands}: put sold goods back on the shelf.
 *
 * <p>Answered with {@code inventory.restocked}.
 *
 * <p>Distinct from {@link ReleaseInventoryCommand}, and the difference matters. A release returns
 * a <em>hold</em> — stock that was set aside but never written off. A restock returns stock that
 * was already deducted and counted as sold. Sending the wrong one either double-counts the units
 * or fails to return them at all, and neither shows up as an error.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class RestockInventoryCommand extends SagaCommand {

    private static final long serialVersionUID = 1L;

    /** Why the goods are coming back, recorded on the reservation. */
    private String reason;
}

package com.commerceflow.common.event;

import java.util.List;
import java.util.UUID;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Put goods a customer sent back onto the shelf.
 *
 * <h2>Why this is not {@link RestockInventoryCommand}</h2>
 *
 * <p>That command is saga compensation for a cancelled order. It restocks <em>every item in the
 * reservation</em> and then marks the reservation returned, which is exactly right when the whole
 * order is being unwound and exactly wrong for a return.
 *
 * <p>Two things would break. A customer returning one of three items would have all three put back
 * on sale, inventing stock the shop does not have. And the reservation would be marked returned, so
 * a second return against the same order weeks later would find nothing sold and quietly do
 * nothing.
 *
 * <p>Same word, different operation — the same distinction as {@link RefundReturnCommand} against
 * {@link RefundPaymentCommand}, and it is not a coincidence: a cancellation unwinds an order whole,
 * a return takes back a part of one.
 *
 * <h2>Not everything that comes back goes back on sale</h2>
 *
 * <p>This command is only sent when an operator says the goods are resellable. A cracked screen
 * comes back to the warehouse and never to the shelf, and deciding that is a person's job — which
 * is why receiving goods and restocking them are one action with an explicit answer rather than an
 * automatic consequence.
 *
 * <h2>Fire and forget, deliberately</h2>
 *
 * <p>There is no reply topic. The outbox guarantees delivery, the consumer is idempotent, and both
 * sides write an audit entry — so a failure is visible in the dead-letter topic and in the audit
 * log rather than silent. A reply loop would double the machinery to tell the return something it
 * does not act on: the money has already gone back either way.
 */
@Getter
@Setter
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
public class RestockReturnedGoodsCommand extends DomainEvent {

    private static final long serialVersionUID = 1L;

    /** The return this settles, and the idempotency key at the inventory side. */
    private UUID returnId;

    private UUID orderId;

    private String orderNumber;

    /**
     * Exactly what is going back, and how much of it.
     *
     * <p>Explicit rather than "the whole order", which is the entire difference from the
     * compensation command. Units go back into the building they were taken from, which the
     * original reservation records.
     */
    private List<OrderLineItem> lines;

    /** The customer's reason, carried through so the audit entry says why stock reappeared. */
    private String reason;

    /** Partitioned by order, so two returns against one order are handled in order. */
    @Override
    public String partitionKey() {
        return orderId == null ? null : orderId.toString();
    }
}

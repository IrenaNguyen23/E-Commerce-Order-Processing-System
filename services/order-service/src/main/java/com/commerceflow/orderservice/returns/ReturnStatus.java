package com.commerceflow.orderservice.returns;

/**
 * Where a return has got to.
 *
 * <p>Deliberately not part of {@code OrderStatus}. That enum is the saga's state machine and every
 * value in it is set by the orchestrator; a return happens after the saga is finished and nothing
 * orchestrates it. Mixing the two would put a state in the machine that the machine never reaches.
 */
public enum ReturnStatus {

    /** The customer has asked. Nobody has looked at it yet. */
    REQUESTED,

    /** Approved: the customer has been told to send the goods back. */
    APPROVED,

    /** Refused, with a reason the customer can read. Terminal. */
    REJECTED,

    /** The goods are back and have been checked. Ready to refund. */
    RECEIVED,

    /**
     * The refund command has gone out and no answer has come back yet.
     *
     * <p>A distinct state rather than staying {@code RECEIVED}, because the difference matters to
     * whoever is looking: one means "somebody needs to press refund", the other means "the money
     * is on its way and pressing it again would send it twice".
     */
    REFUND_PENDING,

    /** The money has gone back. Terminal. */
    REFUNDED,

    /** The customer changed their mind before anyone decided. Terminal. */
    CANCELLED;

    /** Whether anything further can happen to a return in this state. */
    public boolean isTerminal() {
        return this == REJECTED || this == REFUNDED || this == CANCELLED;
    }

    /**
     * Whether the goods have not yet been sent back.
     *
     * <p>Which is what decides whether a customer may still call it off: once a parcel is in the
     * post, cancelling the request would leave a box arriving that nothing expects.
     */
    public boolean isBeforeDispatch() {
        return this == REQUESTED;
    }
}

package com.commerceflow.orderservice.saga.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a saga instance is in its life.
 *
 * <pre>
 *   STARTED ──── every forward step succeeded ────▶ COMPLETED
 *      │                                            (terminal)
 *      │ a step failed
 *      ▼
 *  COMPENSATING ── every undo acknowledged ──────▶ COMPENSATED
 *      │                                            (terminal)
 *      │ a step stopped answering
 *      ▼
 *   STALLED  (parked for an operator; not terminal — it can be resumed)
 * </pre>
 */
public enum SagaState {

    /** Running forward. */
    STARTED,

    /** A step failed; the orchestrator is undoing the steps that had already succeeded. */
    COMPENSATING,

    /** Terminal success: the order completed and the customer was told. */
    COMPLETED,

    /** Terminal failure, cleanly undone: nothing is held and nothing was charged. */
    COMPENSATED,

    /**
     * The current step stopped answering and the command has been re-sent its maximum number of
     * times. Deliberately not automatic beyond this point: a payment step that goes quiet may
     * have taken the money, and cancelling it on a guess is worse than paging a human.
     */
    STALLED;

    private static final Set<SagaState> TERMINAL = EnumSet.of(COMPLETED, COMPENSATED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** @return whether the orchestrator is still expecting a reply in this state. */
    public boolean isRunning() {
        return this == STARTED || this == COMPENSATING;
    }
}

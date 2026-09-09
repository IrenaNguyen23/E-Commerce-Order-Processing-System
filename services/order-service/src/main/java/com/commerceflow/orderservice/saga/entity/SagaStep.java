package com.commerceflow.orderservice.saga.entity;

/**
 * The steps of the order saga, in the order the orchestrator drives them.
 *
 * <p>This enum is the definition of the flow, not a description of it. Changing the order of the
 * saga means editing this file and the orchestrator that walks it — and the whole sequence fits on
 * one screen, which is the property worth protecting as steps are added.
 *
 * <pre>
 *  forward:       RESERVE_INVENTORY ─▶ PROCESS_PAYMENT ─▶ CONFIRM_INVENTORY ─▶ NOTIFY_CUSTOMER
 *  compensation:  RELEASE_INVENTORY (undoes RESERVE_INVENTORY) ─▶ NOTIFY_CUSTOMER
 *  cancellation:  REFUND_PAYMENT ─▶ RELEASE_INVENTORY | RESTOCK_INVENTORY ─▶ NOTIFY_CUSTOMER
 * </pre>
 */
public enum SagaStep {

    /** Hold stock for every line. Answered on {@code inventory.reserved} / {@code inventory.failed}. */
    RESERVE_INVENTORY(false),

    /** Charge the customer. Answered on {@code payment.completed} / {@code payment.failed}. */
    PROCESS_PAYMENT(false),

    /** Turn the hold into a permanent deduction. Answered on {@code inventory.confirmed}. */
    CONFIRM_INVENTORY(false),

    /** Give the held stock back. Answered on {@code inventory.released}. */
    RELEASE_INVENTORY(true),

    /** Send the customer's money back. Answered on {@code payment.refunded}. */
    REFUND_PAYMENT(true),

    /**
     * Put sold goods back on the shelf. Answered on {@code inventory.restocked}.
     *
     * <p>Not interchangeable with {@link #RELEASE_INVENTORY}: that one gives up a hold, this one
     * returns units already written off as sold.
     */
    RESTOCK_INVENTORY(true),

    /**
     * Tell the customer how it ended. Answered on {@code notification.sent}.
     *
     * <p>The last step of both paths, which is why it is neither forward nor compensating: a
     * cancelled order still owes the customer an explanation.
     */
    NOTIFY_CUSTOMER(false);

    private final boolean compensation;

    SagaStep(boolean compensation) {
        this.compensation = compensation;
    }

    /** @return whether this step undoes an earlier one rather than advancing the saga. */
    public boolean isCompensation() {
        return compensation;
    }

    /**
     * What to do when this step has used up its retry budget without answering.
     *
     * <p>The whole question is whether money could have moved by the time this step runs. Before
     * the payment command, nothing has been charged and the saga can safely unwind itself. After
     * it, the outcome is unknown and a human has to decide. Compensation steps are the third
     * case: the decision is already made, so the only correct thing is to keep asking.
     *
     * @see ExhaustionPolicy
     */
    public ExhaustionPolicy onRetriesExhausted() {
        return switch (this) {
            // Nothing was charged; nothing was even asked to be charged.
            case RESERVE_INVENTORY -> ExhaustionPolicy.ABANDON;

            // The customer may or may not have been charged. Not ours to guess.
            case PROCESS_PAYMENT -> ExhaustionPolicy.PARK;

            // The customer was charged. The stock is sold, so holding it is correct — but the
            // write-off still has to happen, and only an operator can find out why it did not.
            case CONFIRM_INVENTORY -> ExhaustionPolicy.PARK;

            // Giving up here is what leaves stock held for an order that is already cancelled.
            case RELEASE_INVENTORY -> ExhaustionPolicy.KEEP_TRYING;

            // The customer is owed money that has not been sent. There is no version of giving
            // up on that which is acceptable, and the refund is idempotent, so it keeps asking.
            case REFUND_PAYMENT -> ExhaustionPolicy.KEEP_TRYING;

            // Same argument as a release: stock that never comes back is stock nobody can sell.
            case RESTOCK_INVENTORY -> ExhaustionPolicy.KEEP_TRYING;

            // Everything is settled; only the customer's email is outstanding.
            case NOTIFY_CUSTOMER -> ExhaustionPolicy.PARK;
        };
    }

    /**
     * The name this step goes by outside the saga: {@code INVENTORY}, {@code PAYMENT} or
     * {@code NOTIFICATION}.
     *
     * <p>This is what lands in {@code OrderResponse.failedStep} and in the customer's email, and
     * it is a published API enum. Three orchestrator steps map onto {@code INVENTORY} because a
     * customer does not need to know whether their order stopped at the reserve, the confirm or
     * the release — and the day a step is renamed or split, no client should have to care.
     */
    public String domain() {
        return switch (this) {
            case RESERVE_INVENTORY, CONFIRM_INVENTORY, RELEASE_INVENTORY,
                 RESTOCK_INVENTORY -> "INVENTORY";
            case PROCESS_PAYMENT, REFUND_PAYMENT -> "PAYMENT";
            case NOTIFY_CUSTOMER -> "NOTIFICATION";
        };
    }
}

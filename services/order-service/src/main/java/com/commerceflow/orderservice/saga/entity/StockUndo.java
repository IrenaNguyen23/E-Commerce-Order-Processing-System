package com.commerceflow.orderservice.saga.entity;

/**
 * How a cancellation has to give stock back.
 *
 * <p>Two operations that look alike and are not interchangeable. Sending the wrong one produces
 * no error at all — just stock that stays held forever, or units credited that were never
 * deducted — which is why the choice is recorded rather than inferred later.
 */
public enum StockUndo {

    /**
     * The units were on hold and never written off. Give the hold up.
     *
     * <p>For an order cancelled at or before {@code PAID}.
     */
    RELEASE(SagaStep.RELEASE_INVENTORY),

    /**
     * The units were written off as sold. Put them back on the shelf.
     *
     * <p>For an order cancelled after it reached {@code COMPLETED}.
     */
    RESTOCK(SagaStep.RESTOCK_INVENTORY);

    private final SagaStep step;

    StockUndo(SagaStep step) {
        this.step = step;
    }

    /** The saga step that carries out this undo. */
    public SagaStep step() {
        return step;
    }
}

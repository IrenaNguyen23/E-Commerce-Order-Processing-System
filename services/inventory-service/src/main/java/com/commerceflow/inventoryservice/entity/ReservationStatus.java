package com.commerceflow.inventoryservice.entity;

/** Lifecycle of a stock reservation inside the saga. */
public enum ReservationStatus {

    /** Stock is held for the order; the payment step is running. */
    RESERVED,

    /** Compensated: the stock went back to available. */
    RELEASED,

    /** The order completed; the reservation became a permanent deduction. */
    CONFIRMED,

    /**
     * The order completed, then was cancelled: sold stock came back on the shelf.
     *
     * <p>Distinct from {@link #RELEASED}, which is a hold given up before anything was written
     * off. Collapsing the two would make "how much did we actually shift" unanswerable.
     */
    RETURNED,

    /** Stock could not be held in the first place. */
    FAILED
}

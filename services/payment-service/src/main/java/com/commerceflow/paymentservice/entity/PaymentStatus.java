package com.commerceflow.paymentservice.entity;

/** Lifecycle of a payment attempt. */
public enum PaymentStatus {

    /** Accepted for processing, not yet settled. */
    PENDING,

    /** The customer was charged. */
    COMPLETED,

    /** The charge was declined or the acquirer errored. */
    FAILED,

    /** A completed charge that was later reversed. */
    REFUNDED
}

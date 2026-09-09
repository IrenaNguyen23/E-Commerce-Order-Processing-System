package com.commerceflow.common.outbox;

/** Lifecycle of a row in the transactional outbox. */
public enum OutboxStatus {

    /** Written inside the business transaction, not yet on the broker. */
    PENDING,

    /** Acknowledged by Kafka. Kept for audit until the retention period expires. */
    PUBLISHED,

    /** Exhausted {@code maxAttempts}; requires operator attention. */
    FAILED
}

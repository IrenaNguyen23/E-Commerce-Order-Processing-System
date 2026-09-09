package com.commerceflow.orderservice.saga.entity;

/** Outcome of one command the orchestrator sent. */
public enum StepStatus {

    /** The command is in the outbox or on its way; no reply yet. */
    SENT,

    /** The participant answered with success. */
    SUCCEEDED,

    /** The participant answered with a business failure. Not an error — a decision. */
    FAILED,

    /** No reply arrived within the deadline, and the retry budget is spent. */
    TIMED_OUT
}

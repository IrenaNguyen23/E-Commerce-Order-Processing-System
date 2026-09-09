package com.commerceflow.orderservice.saga.entity;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One running order saga, persisted.
 *
 * <p>A single place that knows which step the flow is on, which command is outstanding, when that
 * command was due to be answered, and what has to be undone if the next reply is a failure.
 * Everything the orchestrator decides, it decides from here — and everything an operator needs to
 * answer "where is this order" is one row.
 *
 * <p>The id is the order id. One order, one saga, and no lookup table between them — a reply that
 * names its saga names its order, and a duplicate start is caught by the primary key rather than
 * by a race-prone existence check.
 *
 * <p>It lives in the Order Service database on purpose. The orchestrator advances the saga and
 * the order aggregate in the same transaction, so the two can never disagree about whether an
 * order was cancelled. A separate orchestrator database would buy independence and pay for it
 * with exactly the split-brain this pattern exists to prevent.
 */
@Entity
@Table(name = "saga_instance", indexes = {
        @Index(name = "idx_saga_state_deadline", columnList = "state, step_deadline"),
        @Index(name = "idx_saga_order_number", columnList = "order_number")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaInstance {

    /** Equal to the order id. */
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_number", nullable = false, length = 32)
    private String orderNumber;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "user_email", nullable = false, length = 255)
    private String userEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private SagaState state;

    /**
     * The step whose reply the orchestrator is waiting for, or {@code null} once the saga is
     * terminal. A reply naming any other step is late or superseded and is dropped.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", length = 32)
    private SagaStep currentStep;

    /** {@code eventId} of the outstanding command; a reply's {@code causationId} should match. */
    @Column(name = "current_command_id")
    private UUID currentCommandId;

    /** How many times the current step's command has been sent. Starts at 1. */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    /** When the current step stops being merely slow and starts being a problem. */
    @Column(name = "step_deadline")
    private Instant stepDeadline;

    /**
     * The reservation Inventory created, learned from the reserve reply.
     *
     * <p>Held here because it is the handle the compensation needs: without it the orchestrator
     * would have to ask Inventory what to undo, and a compensation that depends on the
     * participant being reachable is not much of a compensation.
     */
    @Column(name = "reservation_id")
    private UUID reservationId;

    /** The payment, learned from the payment reply. */
    @Column(name = "payment_id")
    private UUID paymentId;

    /** The step that decided the saga would not complete. */
    @Enumerated(EnumType.STRING)
    @Column(name = "failed_step", length = 32)
    private SagaStep failedStep;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * How a cancellation must return stock: {@code RELEASE} a hold, or {@code RESTOCK} goods
     * already written off as sold.
     *
     * <p>Decided once, when the cancellation is requested, because the order's own status cannot
     * answer it afterwards — cancelling overwrites it, and the previous value is gone.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "stock_undo", length = 16)
    private StockUndo stockUndo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /**
     * Optimistic lock.
     *
     * <p>Two replies for one saga cannot be processed concurrently — they share a partition — but
     * the timeout scanner runs on a different thread and could collide with a reply that arrives
     * at the same moment. Losing that race rolls the scanner back, which is the right outcome:
     * the reply won, and there is nothing to time out.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** @return whether this reply is the one the saga is currently waiting for. */
    public boolean isAwaiting(SagaStep step) {
        return state.isRunning() && currentStep == step;
    }

    /**
     * Records that a command for {@code step} has just been sent.
     *
     * @param commandId {@code eventId} of the command
     * @param timeout   how long the participant has to answer before the step is chased
     */
    public void awaitReply(SagaStep step, UUID commandId, Duration timeout) {
        Instant now = Instant.now();
        this.currentStep = step;
        this.currentCommandId = commandId;
        this.attempt = 1;
        this.stepDeadline = now.plus(timeout);
        this.updatedAt = now;
    }

    /** Records a re-send of the outstanding command after its deadline passed. */
    public void retryReply(UUID commandId, Duration timeout) {
        Instant now = Instant.now();
        this.currentCommandId = commandId;
        this.attempt += 1;
        this.stepDeadline = now.plus(timeout);
        this.updatedAt = now;
    }

    /** Records why the saga will not complete, and switches it to the compensating path. */
    public void beginCompensation(SagaStep step, String reason) {
        this.state = SagaState.COMPENSATING;
        this.failedStep = step;
        this.failureReason = truncate(reason);
        this.updatedAt = Instant.now();
    }

    /**
     * Closes the saga.
     *
     * <p>Which terminal state it lands in follows from the path it was on, so a caller cannot
     * accidentally mark a compensated saga as successful.
     */
    public void finish() {
        Instant now = Instant.now();
        this.state = this.state == SagaState.COMPENSATING
                ? SagaState.COMPENSATED
                : SagaState.COMPLETED;
        this.currentStep = null;
        this.currentCommandId = null;
        this.stepDeadline = null;
        this.completedAt = now;
        this.updatedAt = now;
    }

    /**
     * Reopens a saga so a cancellation can run through it.
     *
     * <p>A finished saga is reopened rather than a second one started, because there is one flow
     * per order and its step log is the order's history. Splitting a cancellation into a separate
     * saga would leave two half-stories where there should be one.
     */
    public void reopenForCancellation(String reason, StockUndo undo) {
        this.state = SagaState.COMPENSATING;
        this.failureReason = truncate(reason);
        this.stockUndo = undo;
        this.completedAt = null;
        this.updatedAt = Instant.now();
    }

    /** Parks the saga for an operator: the current step exhausted its retries without answering. */
    public void stall() {
        this.state = SagaState.STALLED;
        this.stepDeadline = null;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 255 ? reason : reason.substring(0, 255);
    }
}

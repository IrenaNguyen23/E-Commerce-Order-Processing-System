package com.commerceflow.orderservice.saga.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One command sent and, once it comes back, the reply it got.
 *
 * <p>The saga's audit trail. Reading these rows in {@code created_at} order gives the exact
 * history of a single order — every command, every attempt, every outcome, with the latency
 * between the two halves of each step. That question used to require grepping the logs of four
 * services for a correlation id and hoping none of them had rolled over.
 *
 * <p>Kept as an append-and-close log rather than a mutable status column: a step that was
 * re-sent twice leaves three rows, and that history is exactly what an operator needs when a
 * participant misbehaves.
 */
@Entity
@Table(name = "saga_step_log", indexes = {
        @Index(name = "idx_saga_step_log_saga", columnList = "saga_id, created_at"),
        @Index(name = "idx_saga_step_log_command", columnList = "command_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaStepLog {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "saga_id", nullable = false)
    private UUID sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step", nullable = false, length = 32)
    private SagaStep step;

    /** {@code true} when this step undoes an earlier one. Denormalised so a SQL read is enough. */
    @Column(name = "compensation", nullable = false)
    private boolean compensation;

    /** Which send this was, so a retried step is legible without joining the rows together. */
    @Column(name = "attempt", nullable = false)
    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private StepStatus status;

    /** {@code eventId} of the command that was sent. */
    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    /** {@code eventId} of the reply that closed this step, once one arrived. */
    @Column(name = "reply_event_id")
    private UUID replyEventId;

    /** The participant's reason on a failure, or the timeout note. */
    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When the reply landed. {@code created_at} to here is the step's real latency. */
    @Column(name = "completed_at")
    private Instant completedAt;

    /** Opens a row for a command that is about to be written to the outbox. */
    public static SagaStepLog sent(UUID sagaId, SagaStep step, UUID commandId, int attempt) {
        return SagaStepLog.builder()
                .id(UUID.randomUUID())
                .sagaId(sagaId)
                .step(step)
                .compensation(step.isCompensation())
                .attempt(attempt)
                .status(StepStatus.SENT)
                .commandId(commandId)
                .createdAt(Instant.now())
                .build();
    }

    /** Closes the row with the reply that answered it. */
    public void close(StepStatus outcome, UUID replyEventId, String detail) {
        this.status = outcome;
        this.replyEventId = replyEventId;
        this.detail = truncate(detail);
        this.completedAt = Instant.now();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}

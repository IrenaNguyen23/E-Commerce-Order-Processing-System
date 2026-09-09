package com.commerceflow.common.outbox;

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
 * A message that must reach Kafka exactly as often as its business transaction committed.
 *
 * <p>Written in the same transaction as the aggregate change and relayed asynchronously by
 * {@link OutboxRelay}. Every service owns its own {@code outbox_event} table inside its own
 * database, per ADR-005 and ADR-006.
 */
@Entity
@Table(name = "outbox_event", indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, created_at"),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregate_type, aggregate_id")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Aggregate root that produced the event, e.g. {@code ORDER}. */
    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    /** Identifier of that aggregate; also the default Kafka partition key. */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    /** Business event id, the idempotency key seen by every consumer. */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    /** Logical event name, see {@code EventTypes}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "partition_key", length = 128)
    private String partitionKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Marks the row as delivered. */
    public void markPublished(Instant when) {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = when;
        this.lastError = null;
    }

    /** Records a failed delivery attempt, giving up once {@code maxAttempts} is reached. */
    public void markAttemptFailed(String error, int maxAttempts) {
        this.attempts = this.attempts + 1;
        this.lastError = truncate(error);
        if (this.attempts >= maxAttempts) {
            this.status = OutboxStatus.FAILED;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}

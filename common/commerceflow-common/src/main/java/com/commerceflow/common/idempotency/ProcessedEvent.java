package com.commerceflow.common.idempotency;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Immutable record that a given consumer group has already handled a given event.
 *
 * <p>Written inside the same transaction as the business change, which is what turns the
 * at-least-once delivery of the outbox relay into effectively-once processing.
 */
@Entity
@Table(name = "processed_event", indexes = {
        @Index(name = "idx_processed_event_processed_at", columnList = "processed_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @EmbeddedId
    private ProcessedEventId id;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public static ProcessedEvent of(String consumerGroup, UUID eventId, String eventType) {
        return ProcessedEvent.builder()
                .id(new ProcessedEventId(consumerGroup, eventId))
                .eventType(eventType)
                .processedAt(Instant.now())
                .build();
    }
}

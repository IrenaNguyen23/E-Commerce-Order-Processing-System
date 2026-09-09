package com.commerceflow.common.outbox;

import java.time.Instant;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.kafka.EventPublisher;
import com.commerceflow.common.kafka.KafkaTypeMappings;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The read side of the outbox pattern: relays committed rows to Kafka.
 *
 * <p>Delivery is at-least-once. Consumers deduplicate through
 * {@link com.commerceflow.common.idempotency.IdempotencyService}, which together gives the
 * effectively-once semantics the saga requires.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository repository;
    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;

    /**
     * Claims and publishes one batch.
     *
     * <p>The whole batch runs in a single transaction: the rows stay locked with
     * FOR UPDATE SKIP LOCKED until their status has been updated, so a crash mid-batch simply
     * leaves them PENDING for the next poll or for another replica. Several replicas can relay
     * concurrently without either blocking each other or publishing the same row twice.
     *
     * @return number of rows successfully published
     */
    @Transactional
    public int publishPendingBatch() {
        List<OutboxEvent> batch =
                repository.lockPendingBatch(properties.getMaxAttempts(), properties.getBatchSize());
        if (batch.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent row : batch) {
            if (publish(row)) {
                published++;
            }
        }
        log.debug("Outbox relay published {}/{} row(s)", published, batch.size());
        return published;
    }

    private boolean publish(OutboxEvent row) {
        String previousCorrelation = CorrelationContext.get();
        try {
            CorrelationContext.set(row.getCorrelationId());

            // Deserialising the payload back into its event type is not wasted work: it validates
            // that what was stored still matches the current contract, and it lets the producer
            // serializer attach the logical type header the consumers resolve against.
            DomainEvent event = objectMapper.readValue(
                    row.getPayload(), KafkaTypeMappings.classFor(row.getEventType()));

            eventPublisher.publish(row.getTopic(), row.getPartitionKey(), event,
                    row.getCorrelationId(), properties.getSendTimeout());

            row.markPublished(Instant.now());
            repository.save(row);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordFailure(row, ex);
            return false;
        } catch (Exception ex) {
            recordFailure(row, ex);
            return false;
        } finally {
            CorrelationContext.set(previousCorrelation);
        }
    }

    private void recordFailure(OutboxEvent row, Exception ex) {
        row.markAttemptFailed(ex.getMessage(), properties.getMaxAttempts());
        repository.save(row);
        if (row.getStatus() == OutboxStatus.FAILED) {
            log.error("Outbox row {} ({} -> {}) permanently failed after {} attempts",
                    row.getId(), row.getEventType(), row.getTopic(), row.getAttempts(), ex);
        } else {
            log.warn("Outbox row {} delivery attempt {} failed: {}",
                    row.getId(), row.getAttempts(), ex.getMessage());
        }
    }

    /** Retention housekeeping: drops delivered rows older than the configured retention. */
    @Transactional
    public int purgePublished() {
        Instant cutoff = Instant.now().minus(properties.getRetention());
        int deleted = repository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, cutoff);
        if (deleted > 0) {
            log.info("Outbox retention sweep removed {} published row(s) older than {}", deleted, cutoff);
        }
        return deleted;
    }
}

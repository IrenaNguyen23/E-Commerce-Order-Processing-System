package com.commerceflow.common.idempotency;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Guards Kafka listeners against duplicate delivery.
 *
 * <p>Usage inside a transactional handler:
 *
 * <pre>
 * if (!idempotency.claim(GROUP, event.getEventId(), event.getEventType())) {
 *     return; // already handled, drop silently
 * }
 * ... business change ...
 * </pre>
 *
 * <p>The claim row is inserted in the same transaction as the business change, so either both
 * or neither survive. A concurrent duplicate loses the primary-key race, its transaction rolls
 * back, and the redelivery then sees the committed claim and is dropped.
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyService {

    private final ProcessedEventRepository repository;

    /**
     * Attempts to claim an event for processing.
     *
     * @return {@code true} when this is the first delivery, {@code false} when it is a duplicate
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(String consumerGroup, UUID eventId, String eventType) {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required for idempotent processing");
        }
        ProcessedEventId key = new ProcessedEventId(consumerGroup, eventId);
        if (repository.existsById(key)) {
            log.debug("Duplicate {} event {} for group {}, skipping", eventType, eventId, consumerGroup);
            return false;
        }
        // A concurrent duplicate loses this insert and the DataIntegrityViolationException is
        // allowed to propagate on purpose: the surrounding transaction must roll back anyway, and
        // the Kafka error handler redelivers the record, at which point the committed claim above
        // is visible and the duplicate is dropped cleanly. Swallowing it here would leave the
        // transaction marked rollback-only and fail later with an opaque UnexpectedRollbackException.
        repository.saveAndFlush(ProcessedEvent.of(consumerGroup, eventId, eventType));
        return true;
    }

    /** @return {@code true} when the event has already been processed by this group. */
    @Transactional(readOnly = true)
    public boolean isProcessed(String consumerGroup, UUID eventId) {
        return repository.existsById(new ProcessedEventId(consumerGroup, eventId));
    }

    /** Drops ledger rows older than {@code retention}. */
    @Transactional
    public int purgeOlderThan(Duration retention) {
        Instant cutoff = Instant.now().minus(retention);
        int deleted = repository.deleteByProcessedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("Idempotency ledger sweep removed {} row(s) older than {}", deleted, cutoff);
        }
        return deleted;
    }
}

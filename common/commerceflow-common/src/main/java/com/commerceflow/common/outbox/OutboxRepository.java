package com.commerceflow.common.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence port for the transactional outbox. */
@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims the next batch of undelivered rows.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} lets several service replicas relay concurrently without
     * ever publishing the same row twice and without blocking each other.
     */
    @Query(value = """
            SELECT * FROM outbox_event
            WHERE status = 'PENDING' AND attempts < :maxAttempts
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockPendingBatch(@Param("maxAttempts") int maxAttempts,
                                       @Param("batchSize") int batchSize);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByAggregateTypeAndAggregateIdOrderByCreatedAtAsc(String aggregateType,
                                                                          String aggregateId);

    boolean existsByEventId(UUID eventId);

    /** Retention housekeeping: drops delivered rows older than the cut-off. */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.status = :status AND o.publishedAt < :before")
    int deleteByStatusAndPublishedAtBefore(@Param("status") OutboxStatus status,
                                           @Param("before") Instant before);
}

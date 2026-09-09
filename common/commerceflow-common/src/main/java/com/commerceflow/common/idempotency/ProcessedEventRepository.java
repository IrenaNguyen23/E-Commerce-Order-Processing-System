package com.commerceflow.common.idempotency;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence port for the consumer idempotency ledger. */
@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, ProcessedEventId> {

    /** Retention housekeeping: the ledger only needs to cover the broker retention window. */
    @Modifying
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :before")
    int deleteByProcessedAtBefore(@Param("before") Instant before);
}

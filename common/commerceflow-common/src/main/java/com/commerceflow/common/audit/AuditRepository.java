package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {

    Page<AuditEntry> findAllByOrderByOccurredAtDesc(Pageable pageable);

    Page<AuditEntry> findByActorIdOrderByOccurredAtDesc(UUID actorId, Pageable pageable);

    /** Everything that ever happened to one thing — the usual question after an incident. */
    List<AuditEntry> findByTargetTypeAndTargetIdOrderByOccurredAtDesc(
            String targetType, String targetId);

    /**
     * The read side: filtered, newest first, one page at a time.
     *
     * <h2>Keyset, not page numbers</h2>
     *
     * <p>Paged by {@code (occurredAt, id)} rather than an offset, because the back office merges
     * this stream with the same stream from four other services. Page 3 of one service and page 3
     * of another do not cover the same span of time, so offsets cannot be merged into a single
     * ordered list at all; a position can.
     *
     * <p>{@code id} is only a tiebreak for rows written in the same instant. It is not meaningful
     * ordering on its own — the ids are random — but it makes the sequence total, which is what
     * stops a row being shown twice or skipped between one page and the next.
     *
     * <h2>Every filter is optional</h2>
     *
     * <p>One query rather than a Specification. The filter set is small, fixed, and unlikely to
     * grow: an audit asks who did it, what they did, what they did it to, and when.
     */
    @Query("""
            SELECT a FROM AuditEntry a
             WHERE (:actor IS NULL OR LOWER(a.actorEmail) LIKE LOWER(CONCAT('%', :actor, '%')))
               AND (:action IS NULL OR a.action = :action)
               AND (:targetType IS NULL OR a.targetType = :targetType)
               AND (:targetId IS NULL OR a.targetId = :targetId)
               AND (:correlationId IS NULL OR a.correlationId = :correlationId)
               AND (:from IS NULL OR a.occurredAt >= :from)
               AND (:to IS NULL OR a.occurredAt <= :to)
               AND (:beforeAt IS NULL
                    OR a.occurredAt < :beforeAt
                    OR (a.occurredAt = :beforeAt AND a.id < :beforeId))
             ORDER BY a.occurredAt DESC, a.id DESC
            """)
    List<AuditEntry> search(@Param("actor") String actor,
                            @Param("action") String action,
                            @Param("targetType") String targetType,
                            @Param("targetId") String targetId,
                            @Param("correlationId") String correlationId,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            @Param("beforeAt") Instant beforeAt,
                            @Param("beforeId") UUID beforeId,
                            Pageable pageable);

    /**
     * The actions this service has actually recorded.
     *
     * <p>So the filter in the back office offers what is there rather than a hard-coded list that
     * drifts every time somebody audits a new operation.
     */
    @Query("SELECT DISTINCT a.action FROM AuditEntry a ORDER BY a.action")
    List<String> distinctActions();

    /**
     * Removes entries past their retention.
     *
     * <p>Deliberately a separate, explicit operation rather than something a general sweeper does
     * across every table: how long an audit trail is kept is a compliance decision with a
     * different answer from how long a processed-event ledger is kept, and the two should not
     * share a number.
     */
    @Modifying
    @Query("DELETE FROM AuditEntry a WHERE a.occurredAt < :before")
    int deleteOlderThan(@Param("before") Instant before);
}

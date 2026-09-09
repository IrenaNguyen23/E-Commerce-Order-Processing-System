package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing an operator did.
 *
 * <h2>Append-only, and never written by the thing it describes</h2>
 *
 * <p>Nothing updates or deletes a row here. An audit trail that can be edited answers a different
 * question from the one anybody asks it — "what happened" becomes "what does the system currently
 * claim happened", and the two diverge exactly when it matters.
 *
 * <h2>Local to each service, like the outbox</h2>
 *
 * <p>Every service writes its own rows into its own database. That is the same deliberate
 * duplication as {@code outbox_event} and {@code processed_event}: a shared audit database would be
 * a single table every service writes to synchronously, which is a coupling point the whole system
 * then depends on for an operation that must never fail.
 *
 * <p>The cost is real and worth stating: <b>"everything operator X did" is five queries, not
 * one.</b> The runbook says which database holds what. A single view means a consumer subscribing
 * to an audit topic and building one — worth doing when somebody actually needs it, and not
 * before.
 *
 * <h2>What is deliberately not stored</h2>
 *
 * <p>No before-and-after values. Storing them means storing whatever was in the field, which for a
 * customer record is personal data copied into a table with a long retention — and an erasure
 * request would then have to find and scrub it. {@link #summary} is written by the caller and is
 * expected to name the change without quoting the data: "roles changed to ADMIN", not the email
 * address it happened to.
 */
@Entity
@Table(name = "admin_audit_log", indexes = {
        @Index(name = "idx_audit_actor", columnList = "actor_id, occurred_at"),
        @Index(name = "idx_audit_target", columnList = "target_type, target_id"),
        @Index(name = "idx_audit_occurred", columnList = "occurred_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEntry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /**
     * The account that did it, or {@code null} for something the system did on a schedule.
     *
     * <p>Nullable rather than a synthetic "system" id, because those two cases genuinely differ
     * and a reader should not have to know a magic UUID to tell them apart.
     */
    @Column(name = "actor_id")
    private UUID actorId;

    /** Copied at the time. The account may be renamed, or later erased, and this must still read. */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    /** What was done: {@code ORDER_CANCELLED}, {@code PRODUCT_UPDATED}, {@code ROLES_CHANGED}. */
    @Column(name = "action", nullable = false, length = 64)
    private String action;

    /** What it was done to: {@code ORDER}, {@code PRODUCT}, {@code USER}, {@code COUPON}. */
    @Column(name = "target_type", nullable = false, length = 32)
    private String targetType;

    @Column(name = "target_id", length = 64)
    private String targetId;

    /**
     * One sentence naming the change, written for a person reading it months later.
     *
     * <p>Not a serialised diff — see the class comment for why storing the old and new values is a
     * privacy problem rather than a convenience.
     */
    @Column(name = "summary", length = 500)
    private String summary;

    /**
     * Where the request came from.
     *
     * <p>Nullable because plenty of these come from a scheduled job with no request behind them.
     */
    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    /** Ties an entry to the request that produced it, and to the log lines from that request. */
    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;
}

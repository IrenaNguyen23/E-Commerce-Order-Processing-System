package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit entry, as the back office sees it.
 *
 * @param service which service recorded it — the log is local to each, so a merged view has to say
 *     where each row came from
 * @param actorId the account that did it, or {@code null} for something the system did on its own.
 *     Null is an answer, not a missing value: it means nobody did this, an event arrived or a job
 *     ran.
 * @param actorEmail copied at the time, so it still reads after the account is renamed or erased
 * @param occurredAt paired with {@code id} it is this row's position in the stream, which is what
 *     the next page is asked for
 */
public record AuditEntryResponse(
        UUID id,
        String service,
        UUID actorId,
        String actorEmail,
        String action,
        String targetType,
        String targetId,
        String summary,
        String sourceIp,
        String correlationId,
        Instant occurredAt) {

    public static AuditEntryResponse of(AuditEntry entry, String service) {
        return new AuditEntryResponse(
                entry.getId(),
                service,
                entry.getActorId(),
                entry.getActorEmail(),
                entry.getAction(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getSummary(),
                entry.getSourceIp(),
                entry.getCorrelationId(),
                entry.getOccurredAt());
    }
}

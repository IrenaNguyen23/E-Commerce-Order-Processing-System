package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Reading the audit trail.
 *
 * <h2>Read-only, and separate from the thing that writes it</h2>
 *
 * <p>{@link AuditService} writes and knows nothing about reading; this reads and cannot write.
 * They are separate because they answer to different things: the write path joins whatever
 * transaction the caller is in, so the audit row commits with the change it describes, while this
 * side is a plain read-only query with no such obligation.
 *
 * <h2>One service's rows only</h2>
 *
 * <p>Every service answers for its own database. A question that spans services — "everything this
 * administrator did" — is five calls, merged by the caller. That is the cost of keeping the write
 * path free of a shared dependency, and it is paid here rather than there on purpose: a slow or
 * incomplete audit search is an inconvenience, an audit write that can fail is a hole in the
 * record.
 */
@RequiredArgsConstructor
public class AuditQueryService {

    /**
     * Ceiling on one page.
     *
     * <p>The back office asks for far less. It exists so a hand-written request cannot ask for the
     * whole two-year table in one response and take the service down with it.
     */
    public static final int MAX_PAGE_SIZE = 200;

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final AuditRepository repository;

    /** Which service these rows come from. Copied onto every row so a merged view can say. */
    private final String serviceName;

    /**
     * A page of entries, newest first.
     *
     * <p>Fetches one row more than asked for and drops it. That single extra row is what makes
     * {@code hasMore} truthful without a count query — the alternative, "we returned a full page
     * so there is probably more", shows a next-page control that leads to nothing.
     */
    @Transactional(readOnly = true)
    public AuditSlice search(AuditSearch criteria) {
        int size = normaliseSize(criteria.size());

        List<AuditEntry> found = repository.search(
                blankToNull(criteria.actor()),
                blankToNull(criteria.action()),
                blankToNull(criteria.targetType()),
                blankToNull(criteria.targetId()),
                blankToNull(criteria.correlationId()),
                criteria.from(),
                criteria.to(),
                criteria.beforeAt(),
                // Only a tiebreak, and only consulted when beforeAt matches exactly. A position
                // with no id still pages correctly; it can merely repeat a row that shares its
                // predecessor's instant to the microsecond.
                criteria.beforeId(),
                PageRequest.of(0, size + 1));

        if (found.isEmpty()) {
            return AuditSlice.empty();
        }

        boolean hasMore = found.size() > size;
        List<AuditEntry> page = hasMore ? found.subList(0, size) : found;

        List<AuditEntryResponse> entries = new ArrayList<>(page.size());
        for (AuditEntry entry : page) {
            entries.add(AuditEntryResponse.of(entry, serviceName));
        }

        AuditEntry last = page.get(page.size() - 1);
        return new AuditSlice(entries, hasMore,
                hasMore ? last.getOccurredAt() : null,
                hasMore ? last.getId() : null);
    }

    /** The actions this service has actually recorded, for the filter in the back office. */
    @Transactional(readOnly = true)
    public List<String> actions() {
        return repository.distinctActions();
    }

    private static int normaliseSize(Integer requested) {
        if (requested == null || requested < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }

    /**
     * Treats an empty filter as an absent one.
     *
     * <p>A form that submits {@code action=} means "no filter", not "entries whose action is the
     * empty string" — and without this the second reading wins and the screen goes blank.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * What to look for.
     *
     * @param actor matched loosely against the actor's email, because somebody investigating types
     *     a name fragment rather than a UUID
     * @param beforeAt with {@code beforeId}, the position to continue from
     */
    public record AuditSearch(
            String actor,
            String action,
            String targetType,
            String targetId,
            String correlationId,
            Instant from,
            Instant to,
            Instant beforeAt,
            UUID beforeId,
            Integer size) {
    }
}

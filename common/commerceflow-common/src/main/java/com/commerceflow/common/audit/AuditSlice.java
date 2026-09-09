package com.commerceflow.common.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A page of audit entries, and where to continue from.
 *
 * <h2>Why not a total count</h2>
 *
 * <p>There is no {@code totalElements} here, and that is deliberate. Counting would mean a second
 * query over a table that grows for two years, on every page, to produce a number the reader does
 * not act on — and a merged count across five services would be five such queries. {@code hasMore}
 * answers the only question the UI actually asks.
 *
 * @param nextBeforeAt position to continue from, or {@code null} at the end of the stream. Sent
 *     back as {@code beforeAt}/{@code beforeId} on the next request — the pair is a position, not
 *     a page number, so it stays correct when the caller is merging this stream with four others.
 */
public record AuditSlice(
        List<AuditEntryResponse> entries,
        boolean hasMore,
        Instant nextBeforeAt,
        UUID nextBeforeId) {

    /** An empty result, at the end of the stream. */
    public static AuditSlice empty() {
        return new AuditSlice(List.of(), false, null, null);
    }
}

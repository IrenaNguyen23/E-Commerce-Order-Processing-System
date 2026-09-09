package com.commerceflow.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import com.commerceflow.common.audit.AuditQueryService.AuditSearch;

/**
 * Reading the trail.
 *
 * <p>Almost everything here is about paging, because paging is where this goes wrong: the back
 * office merges five of these streams into one list, and a page that silently skips or repeats a
 * row produces an audit that is quietly incomplete rather than obviously broken.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");

    @Mock
    private AuditRepository repository;

    private AuditQueryService service;

    @BeforeEach
    void setUp() {
        service = new AuditQueryService(repository, "order-service");
    }

    @Test
    @DisplayName("asks for one row more than the page, and does not return it")
    void fetchesOneExtraToKnowWhetherThereIsMore() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(entries(11));

        AuditSlice slice = service.search(search(10));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                pageable.capture());

        // The extra row is how hasMore becomes a fact rather than a guess. Without it the only
        // options are a count query on every request, or a next-page control that leads nowhere.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(11);
        assertThat(slice.entries()).hasSize(10);
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    @DisplayName("the last page says so, and offers no position to continue from")
    void lastPageHasNoCursor() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(entries(4));

        AuditSlice slice = service.search(search(10));

        assertThat(slice.entries()).hasSize(4);
        assertThat(slice.hasMore()).isFalse();
        // Null rather than the last row's position: a cursor here would invite one more request
        // that can only come back empty.
        assertThat(slice.nextBeforeAt()).isNull();
        assertThat(slice.nextBeforeId()).isNull();
    }

    @Test
    @DisplayName("the position to continue from is the last row shown, not the one held back")
    void cursorPointsAtTheLastVisibleRow() {
        List<AuditEntry> found = entries(11);
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(found);

        AuditSlice slice = service.search(search(10));

        // Taking the position from the extra row would skip it entirely on the next page — one
        // audit entry that no page ever shows, which is the worst possible bug in this feature.
        AuditEntry lastVisible = found.get(9);
        assertThat(slice.nextBeforeAt()).isEqualTo(lastVisible.getOccurredAt());
        assertThat(slice.nextBeforeId()).isEqualTo(lastVisible.getId());
    }

    @Test
    @DisplayName("an empty result is the end of the stream, not an error")
    void emptyIsFine() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of());

        AuditSlice slice = service.search(search(10));

        assertThat(slice.entries()).isEmpty();
        assertThat(slice.hasMore()).isFalse();
        assertThat(slice.nextBeforeAt()).isNull();
    }

    @Test
    @DisplayName("a blank filter is no filter")
    void blankFiltersAreDropped() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of());

        service.search(new AuditSearch("  ", "", null, "   ", "", null, null, null, null, 10));

        // A form that submits action= means "no filter", not "entries whose action is the empty
        // string". Passing the empty string through matches nothing and the screen goes blank.
        verify(repository).search(isNull(), isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("filters that are set are trimmed and passed through")
    void realFiltersReachTheQuery() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of());

        UUID beforeId = UUID.randomUUID();
        service.search(new AuditSearch(" ops@commerceflow.io ", "ROLES_CHANGED", "USER",
                "abc", "corr-1", NOW.minus(1, ChronoUnit.DAYS), NOW, NOW, beforeId, 10));

        verify(repository).search(eq("ops@commerceflow.io"), eq("ROLES_CHANGED"), eq("USER"),
                eq("abc"), eq("corr-1"), eq(NOW.minus(1, ChronoUnit.DAYS)), eq(NOW),
                eq(NOW), eq(beforeId), any(Pageable.class));
    }

    @Test
    @DisplayName("a caller cannot ask for the whole table in one response")
    void pageSizeIsCapped() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of());

        service.search(search(100_000));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                pageable.capture());

        // Capped, not refused: an oversized page is a client being greedy, not a client being
        // wrong, and failing the request teaches nobody anything.
        assertThat(pageable.getValue().getPageSize())
                .isEqualTo(AuditQueryService.MAX_PAGE_SIZE + 1);
    }

    @Test
    @DisplayName("a missing or nonsensical size falls back to the default")
    void sizeDefaultsSensibly() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of());

        service.search(search(null));
        service.search(search(0));
        service.search(search(-5));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository, org.mockito.Mockito.times(3))
                .search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                        pageable.capture());

        assertThat(pageable.getAllValues()).allSatisfy(page ->
                assertThat(page.getPageSize()).isEqualTo(51));
    }

    @Test
    @DisplayName("every row says which service it came from")
    void rowsCarryTheServiceName() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(entries(3));

        AuditSlice slice = service.search(search(10));

        // The back office merges five of these streams. Without this the merged list cannot say
        // where anything happened, which is half the answer.
        assertThat(slice.entries()).allSatisfy(entry ->
                assertThat(entry.service()).isEqualTo("order-service"));
    }

    @Test
    @DisplayName("an entry with no actor keeps its null rather than inventing one")
    void systemActionsHaveNoActor() {
        AuditEntry systemAction = AuditEntry.builder()
                .id(UUID.randomUUID()).actorId(null).actorEmail(null)
                .action("USER_ERASED").targetType("USER").targetId("x")
                .summary("Anonymised 2 order(s)").occurredAt(NOW).build();
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(Pageable.class))).thenReturn(List.of(systemAction));

        AuditSlice slice = service.search(search(10));

        // Null is the answer to "who did this", and the answer is nobody: an event arrived, or a
        // job ran. A synthetic "system" actor would make that indistinguishable from an account.
        assertThat(slice.entries().get(0).actorId()).isNull();
        assertThat(slice.entries().get(0).actorEmail()).isNull();
    }

    private static AuditSearch search(Integer size) {
        return new AuditSearch(null, null, null, null, null, null, null, null, null, size);
    }

    /** Newest first, one second apart, as the query returns them. */
    private static List<AuditEntry> entries(int count) {
        List<AuditEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            entries.add(AuditEntry.builder()
                    .id(UUID.randomUUID())
                    .actorId(UUID.randomUUID())
                    .actorEmail("ops@commerceflow.io")
                    .action("ORDER_CANCELLED")
                    .targetType("ORDER")
                    .targetId("order-" + index)
                    .summary("Cancelled on the customer's request")
                    .occurredAt(NOW.minusSeconds(index))
                    .build());
        }
        return entries;
    }
}

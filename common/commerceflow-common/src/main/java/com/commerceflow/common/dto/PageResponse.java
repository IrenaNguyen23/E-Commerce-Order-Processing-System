package com.commerceflow.common.dto;

import java.util.List;

/**
 * Transport friendly page envelope.
 *
 * <p>Deliberately decoupled from {@code org.springframework.data.domain.Page} so this library can
 * also be used by modules that do not depend on Spring Data (for example the reactive gateway).
 *
 * @param <T> element type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last) {

    public static <T> PageResponse<T> of(List<T> content, int page, int size, long totalElements) {
        int safeSize = Math.max(size, 1);
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        return new PageResponse<>(
                content == null ? List.of() : List.copyOf(content),
                page,
                size,
                totalElements,
                totalPages,
                page == 0,
                page >= totalPages - 1);
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return of(List.of(), page, size, 0L);
    }
}

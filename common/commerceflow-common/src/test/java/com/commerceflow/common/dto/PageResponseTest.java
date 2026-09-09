package com.commerceflow.common.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PageResponseTest {

    @Test
    @DisplayName("page metadata is derived from size and total")
    void derivesMetadata() {
        PageResponse<String> page = PageResponse.of(List.of("a", "b"), 0, 2, 5);

        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isFalse();
    }

    @Test
    @DisplayName("the final page is flagged as last")
    void marksLastPage() {
        PageResponse<String> page = PageResponse.of(List.of("e"), 2, 2, 5);

        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.first()).isFalse();
        assertThat(page.last()).isTrue();
    }

    @Test
    @DisplayName("an empty result is both the first and the last page")
    void handlesEmptyResult() {
        PageResponse<String> page = PageResponse.empty(0, 20);

        assertThat(page.content()).isEmpty();
        assertThat(page.totalPages()).isZero();
        assertThat(page.first()).isTrue();
        assertThat(page.last()).isTrue();
    }
}

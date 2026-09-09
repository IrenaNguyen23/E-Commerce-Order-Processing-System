package com.commerceflow.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final String GROUP = "order-service";

    @Mock
    private ProcessedEventRepository repository;

    @InjectMocks
    private IdempotencyService idempotencyService;

    @Test
    @DisplayName("the first delivery is claimed and recorded")
    void firstDeliveryIsClaimed() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(any(ProcessedEventId.class))).thenReturn(false);

        boolean claimed = idempotencyService.claim(GROUP, eventId, "PAYMENT_COMPLETED");

        assertThat(claimed).isTrue();
        verify(repository).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a redelivery is dropped without touching the database again")
    void redeliveryIsDropped() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(any(ProcessedEventId.class))).thenReturn(true);

        boolean claimed = idempotencyService.claim(GROUP, eventId, "PAYMENT_COMPLETED");

        assertThat(claimed).isFalse();
        verify(repository, never()).saveAndFlush(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("losing the insert race propagates so the transaction rolls back and Kafka redelivers")
    void concurrentDuplicatePropagates() {
        UUID eventId = UUID.randomUUID();
        when(repository.existsById(any(ProcessedEventId.class))).thenReturn(false);
        when(repository.saveAndFlush(any(ProcessedEvent.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> idempotencyService.claim(GROUP, eventId, "PAYMENT_COMPLETED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("an event without an id cannot be deduplicated and is rejected")
    void missingEventIdIsRejected() {
        assertThatThrownBy(() -> idempotencyService.claim(GROUP, null, "PAYMENT_COMPLETED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

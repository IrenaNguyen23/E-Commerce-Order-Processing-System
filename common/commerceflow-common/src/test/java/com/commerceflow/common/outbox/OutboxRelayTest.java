package com.commerceflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.commerceflow.common.constant.EventTypes;
import com.commerceflow.common.constant.KafkaTopics;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.event.OrderCreatedEvent;
import com.commerceflow.common.kafka.EventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxRepository repository;

    @Mock
    private EventPublisher eventPublisher;

    @Captor
    private ArgumentCaptor<DomainEvent> eventCaptor;

    private OutboxRelay relay;
    private OutboxProperties properties;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        properties = new OutboxProperties();
        properties.setMaxAttempts(3);
        relay = new OutboxRelay(repository, eventPublisher, objectMapper, properties);
    }

    @Test
    @DisplayName("an empty outbox does not touch the broker")
    void emptyBatchIsANoOp() {
        when(repository.lockPendingBatch(properties.getMaxAttempts(), properties.getBatchSize()))
                .thenReturn(List.of());

        assertThat(relay.publishPendingBatch()).isZero();
    }

    @Test
    @DisplayName("a pending row is published to its topic and marked PUBLISHED")
    void publishesPendingRow() throws Exception {
        OutboxEvent row = pendingRow();
        when(repository.lockPendingBatch(properties.getMaxAttempts(), properties.getBatchSize()))
                .thenReturn(List.of(row));

        int published = relay.publishPendingBatch();

        assertThat(published).isEqualTo(1);
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isNotNull();

        verify(eventPublisher).publish(eq(KafkaTopics.ORDER_CREATED), eq(row.getPartitionKey()),
                eventCaptor.capture(), eq("corr-1"), any(Duration.class));

        // The payload is rehydrated into its event type, which is also what validates that the
        // stored JSON still matches the current contract.
        assertThat(eventCaptor.getValue()).isInstanceOf(OrderCreatedEvent.class);
        assertThat(((OrderCreatedEvent) eventCaptor.getValue()).getOrderNumber()).isEqualTo("CF-1");
    }

    @Test
    @DisplayName("a broker failure increments attempts and leaves the row PENDING for retry")
    void retriesOnFailure() throws Exception {
        OutboxEvent row = pendingRow();
        when(repository.lockPendingBatch(properties.getMaxAttempts(), properties.getBatchSize()))
                .thenReturn(List.of(row));
        doThrow(new ExecutionException("broker down", new IllegalStateException()))
                .when(eventPublisher)
                .publish(anyString(), anyString(), any(DomainEvent.class), anyString(),
                        any(Duration.class));

        assertThat(relay.publishPendingBatch()).isZero();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).isNotNull();
    }

    @Test
    @DisplayName("a row is parked in FAILED once the attempt budget is exhausted")
    void parksAfterMaxAttempts() throws Exception {
        OutboxEvent row = pendingRow();
        row.setAttempts(properties.getMaxAttempts() - 1);
        when(repository.lockPendingBatch(properties.getMaxAttempts(), properties.getBatchSize()))
                .thenReturn(List.of(row));
        doThrow(new ExecutionException("broker down", new IllegalStateException()))
                .when(eventPublisher)
                .publish(anyString(), anyString(), any(DomainEvent.class), anyString(),
                        any(Duration.class));

        relay.publishPendingBatch();

        assertThat(row.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(properties.getMaxAttempts());
    }

    @Test
    @DisplayName("one failing row does not stop the rest of the batch")
    void oneFailureDoesNotBlockTheBatch() throws Exception {
        OutboxEvent poison = pendingRow();
        OutboxEvent healthy = pendingRow();
        when(repository.lockPendingBatch(properties.getMaxAttempts(), properties.getBatchSize()))
                .thenReturn(List.of(poison, healthy));
        doThrow(new ExecutionException("broker down", new IllegalStateException()))
                .doNothing()
                .when(eventPublisher)
                .publish(anyString(), anyString(), any(DomainEvent.class), anyString(),
                        any(Duration.class));

        assertThat(relay.publishPendingBatch()).isEqualTo(1);
        assertThat(poison.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(healthy.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @DisplayName("the retention sweep only removes delivered rows")
    void purgeTargetsPublishedRowsOnly() {
        when(repository.deleteByStatusAndPublishedAtBefore(eq(OutboxStatus.PUBLISHED),
                any(Instant.class))).thenReturn(7);

        assertThat(relay.purgePublished()).isEqualTo(7);
        verify(repository).deleteByStatusAndPublishedAtBefore(eq(OutboxStatus.PUBLISHED),
                any(Instant.class));
    }

    private static OutboxEvent pendingRow() {
        UUID orderId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","eventType":"ORDER_CREATED","timestamp":"2026-08-26T10:00:00Z",\
                "correlationId":"corr-1","orderId":"%s","orderNumber":"CF-1","totalAmount":100,\
                "currency":"EUR"}""".formatted(UUID.randomUUID(), orderId);

        return OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("ORDER")
                .aggregateId(orderId.toString())
                .eventId(UUID.randomUUID())
                .eventType(EventTypes.ORDER_CREATED)
                .topic(KafkaTopics.ORDER_CREATED)
                .partitionKey(orderId.toString())
                .payload(payload)
                .correlationId("corr-1")
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .createdAt(Instant.now())
                .build();
    }
}

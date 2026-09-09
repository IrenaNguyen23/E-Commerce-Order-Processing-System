package com.commerceflow.common.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

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
import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.event.OrderCreatedEvent;
import com.commerceflow.common.event.OrderLineItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    @Mock
    private OutboxRepository repository;

    @Captor
    private ArgumentCaptor<OutboxEvent> rowCaptor;

    private OutboxService outboxService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        outboxService = new OutboxService(repository, objectMapper);
        when(repository.save(any(OutboxEvent.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("an appended event is routed to its topic with a completed envelope")
    void appendCompletesTheEnvelope() throws Exception {
        UUID orderId = UUID.randomUUID();
        CorrelationContext.set("corr-42");
        try {
            OrderCreatedEvent event = OrderCreatedEvent.builder()
                    .orderId(orderId)
                    .orderNumber("CF-1")
                    .userId(UUID.randomUUID())
                    .userEmail("ada@commerceflow.io")
                    .totalAmount(new BigDecimal("199.99"))
                    .currency("EUR")
                    .items(List.of(OrderLineItem.builder()
                            .productId(UUID.randomUUID())
                            .sku("SKU-1")
                            .quantity(2)
                            .unitPrice(new BigDecimal("99.995"))
                            .build()))
                    .build();

            outboxService.append("ORDER", orderId.toString(), event);
        } finally {
            CorrelationContext.clear();
        }

        org.mockito.Mockito.verify(repository).save(rowCaptor.capture());
        OutboxEvent row = rowCaptor.getValue();

        assertThat(row.getTopic()).isEqualTo(KafkaTopics.ORDER_CREATED);
        assertThat(row.getEventType()).isEqualTo(EventTypes.ORDER_CREATED);
        assertThat(row.getAggregateType()).isEqualTo("ORDER");
        assertThat(row.getAggregateId()).isEqualTo(orderId.toString());
        assertThat(row.getPartitionKey()).isEqualTo(orderId.toString());
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getCorrelationId()).isEqualTo("corr-42");
        assertThat(row.getEventId()).isNotNull();
        assertThat(row.getCreatedAt()).isNotNull();

        OrderCreatedEvent restored = objectMapper.readValue(row.getPayload(), OrderCreatedEvent.class);
        assertThat(restored.getOrderId()).isEqualTo(orderId);
        assertThat(restored.getEventType()).isEqualTo(EventTypes.ORDER_CREATED);
        assertThat(restored.getCorrelationId()).isEqualTo("corr-42");
        assertThat(restored.getTimestamp()).isNotNull();
        assertThat(restored.getItems()).hasSize(1);
    }

    @Test
    @DisplayName("a pre-assigned event id is preserved so retries stay idempotent")
    void preservesExplicitEventId() {
        UUID orderId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .eventId(eventId)
                .orderId(orderId)
                .totalAmount(BigDecimal.TEN)
                .currency("EUR")
                .build();

        OutboxEvent row = outboxService.append("ORDER", orderId.toString(), event);

        assertThat(row.getEventId()).isEqualTo(eventId);
    }
}

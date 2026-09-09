package com.commerceflow.common.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.kafka.KafkaTypeMappings;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The write side of the outbox pattern.
 *
 * <p>{@link Propagation#MANDATORY} is deliberate: appending an event outside the business
 * transaction would reintroduce the dual-write problem the pattern exists to remove, so a
 * misuse fails fast at development time instead of silently losing messages in production.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Appends an event to the outbox of the current transaction.
     *
     * @param aggregateType aggregate root that produced the event, e.g. ORDER
     * @param aggregateId   identifier of that aggregate
     * @param event         the event; its envelope is completed here
     * @return the persisted outbox row
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(String aggregateType, String aggregateId, DomainEvent event) {
        String alias = KafkaTypeMappings.aliasFor(event.getClass());
        String topic = KafkaTypeMappings.topicFor(alias);
        String correlationId = CorrelationContext.get();
        event.initEnvelope(alias, correlationId);

        String payload = serialize(event);
        String partitionKey = event.partitionKey() != null ? event.partitionKey() : aggregateId;

        OutboxEvent row = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventId(event.getEventId())
                .eventType(alias)
                .topic(topic)
                .partitionKey(partitionKey)
                .payload(payload)
                .correlationId(correlationId)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .createdAt(Instant.now())
                .build();

        OutboxEvent saved = repository.save(row);
        log.debug("Outbox append {} {} -> {} (eventId={})", aggregateType, aggregateId, topic,
                saved.getEventId());
        return saved;
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Unable to serialise outbox payload for " + event.getClass().getName(), ex);
        }
    }
}

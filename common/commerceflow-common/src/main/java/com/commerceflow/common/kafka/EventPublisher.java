package com.commerceflow.common.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.event.DomainEvent;

/**
 * The single seam through which this platform writes to Kafka.
 *
 * <p>It exists for two reasons.
 *
 * <p>First, typing. Spring Boot auto-configures its template as {@code KafkaTemplate<?, ?>}, so
 * every injection point that asks for a concrete parameterisation is relying on the leniency of
 * generic autowiring. Asking for exactly the type Boot declares, and narrowing once here behind a
 * documented cast, removes that dependency entirely — the erasure is real, the cast is safe, and
 * it happens in one place instead of at every call site.
 *
 * <p>Second, testability. A collaborator with two methods is far easier to stub than a
 * {@code KafkaTemplate}, so the outbox relay tests stay about the relay.
 */
public class EventPublisher {

    private final KafkaOperations<Object, Object> operations;

    @SuppressWarnings("unchecked")
    public EventPublisher(KafkaTemplate<?, ?> kafkaTemplate) {
        // Safe: Kafka generics are erased at runtime, and every producer on this platform is
        // configured with a String key serializer and the shared JSON value serializer.
        this.operations = (KafkaOperations<Object, Object>) kafkaTemplate;
    }

    /**
     * Publishes and waits for the broker acknowledgement.
     *
     * <p>Synchronous on purpose: the caller is the outbox relay, which must know whether the
     * record was accepted before it marks the row as published. Fire-and-forget here would put
     * the dual-write problem back exactly where the outbox removed it.
     *
     * @throws ExecutionException   when the broker rejected the record
     * @throws TimeoutException     when the acknowledgement did not arrive in time
     * @throws InterruptedException when the calling thread was interrupted
     */
    public void publish(String topic, String partitionKey, DomainEvent event, String correlationId,
                        Duration timeout)
            throws ExecutionException, InterruptedException, TimeoutException {

        ProducerRecord<Object, Object> record = new ProducerRecord<>(topic, partitionKey, event);
        if (correlationId != null && !correlationId.isBlank()) {
            record.headers().add(new RecordHeader(CorrelationContext.KAFKA_HEADER,
                    correlationId.getBytes(StandardCharsets.UTF_8)));
        }
        operations.send(record).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Publishes without waiting. Used by tests and by callers that already have their own
     * durability guarantee; the saga itself always goes through the outbox.
     */
    public void publishAsync(String topic, String partitionKey, DomainEvent event) {
        operations.send(new ProducerRecord<>(topic, partitionKey, event));
    }

    /** Flushes any buffered records. */
    public void flush() {
        operations.flush();
    }
}

package com.commerceflow.common.testsupport;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.commerceflow.common.event.DomainEvent;
import com.commerceflow.common.kafka.EventPublisher;
import com.commerceflow.common.kafka.KafkaTypeMappings;

/**
 * Base class for the saga integration tests.
 *
 * <p>Wires the service under test to real PostgreSQL, Kafka and Redis containers. Nothing is
 * mocked below the service boundary: the point of these tests is to prove that Flyway, Hibernate,
 * the outbox relay and the Kafka serializers actually agree with each other, which no amount of
 * unit testing can establish.
 *
 * <p>Tagged {@code integration}, so Surefire skips it and Failsafe runs it under
 * {@code mvn verify -Pintegration}. A developer without Docker still gets a green {@code mvn test}.
 *
 * <p>Under orchestration a test plays one of two roles. Testing a <b>participant</b> means
 * sending it a command with {@link #command} and asserting on the reply it puts back on the bus.
 * Testing the <b>orchestrator</b> means letting it issue a command, then answering with
 * {@link #reply} and asserting on the command it issues next. Both directions carry a
 * {@code sagaId}: a message without one is not part of a saga and is ignored by design, so
 * forgetting it in a test would silently prove nothing.
 */
@Tag("integration")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractSagaIntegrationTest {

    /** Generous enough for the outbox relay poll plus a Kafka round trip on a loaded CI runner. */
    protected static final Duration SAGA_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    protected EventPublisher eventPublisher;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> CommerceFlowContainers.postgres().getJdbcUrl());
        registry.add("spring.datasource.username",
                () -> CommerceFlowContainers.postgres().getUsername());
        registry.add("spring.datasource.password",
                () -> CommerceFlowContainers.postgres().getPassword());

        registry.add("spring.kafka.bootstrap-servers",
                () -> CommerceFlowContainers.kafka().getBootstrapServers());

        registry.add("spring.data.redis.host", CommerceFlowContainers::redisHost);
        registry.add("spring.data.redis.port", CommerceFlowContainers::redisPort);

        // Poll aggressively so a test does not spend most of its time waiting for the relay.
        registry.add("commerceflow.outbox.poll-interval-ms", () -> "200");
        registry.add("commerceflow.security.jwt.secret",
                () -> "commerceflow-integration-test-secret-key-of-32-plus-bytes");
        registry.add("management.tracing.enabled", () -> "false");
    }

    /**
     * Publishes an event onto a topic exactly the way a real service would — same serializer,
     * same logical type header — so a mismatch in the wire contract shows up here rather than in
     * production.
     */
    protected void publish(String topic, DomainEvent event) {
        if (event.getEventId() == null) {
            event.setEventId(UUID.randomUUID());
        }
        if (event.getEventType() == null) {
            event.setEventType(KafkaTypeMappings.aliasFor(event.getClass()));
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(java.time.Instant.now());
        }
        eventPublisher.publishAsync(topic, event.partitionKey(), event);
        eventPublisher.flush();
    }

    /**
     * Sends a command to a participant, standing in for the orchestrator.
     *
     * @param sagaId the saga the command belongs to; echoed back on the participant's reply
     */
    protected <T extends DomainEvent> T command(String topic, T command, UUID sagaId) {
        command.setSagaId(sagaId);
        publish(topic, command);
        return command;
    }

    /**
     * Answers a command the orchestrator issued, standing in for a participant.
     *
     * <p>{@code causationId} is set to the command's id exactly as a real participant's
     * {@code replyTo} would, so the step log closes the row the reply actually answers.
     */
    protected <T extends DomainEvent> T reply(String topic, T reply, DomainEvent toCommand) {
        reply.replyTo(toCommand);
        publish(topic, reply);
        return reply;
    }

    /** Same, for a reply invented by the test rather than caused by a captured command. */
    protected <T extends DomainEvent> T reply(String topic, T reply, UUID sagaId) {
        reply.setSagaId(sagaId);
        publish(topic, reply);
        return reply;
    }

    /**
     * Polls until a message of {@code type} arrives, or fails the test.
     *
     * <p>Preferred over {@link #drain} whenever the topic can carry more than one message type —
     * every command topic does. Draining the first batch and casting it would pass or fail on
     * arrival order, which is not something a test should depend on.
     */
    protected <T> T awaitMessage(Consumer<String, Object> consumer, Class<T> type,
                                 Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        List<String> seen = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(300));
            for (ConsumerRecord<String, Object> record : records) {
                Object value = record.value();
                if (type.isInstance(value)) {
                    return type.cast(value);
                }
                seen.add(value == null ? "null" : value.getClass().getSimpleName());
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " within " + timeout
                + "; saw " + seen);
    }

    /**
     * Opens a consumer positioned at the end of {@code topics}, so it only sees what the test
     * publishes next and never a leftover from an earlier test in the same class.
     */
    protected Consumer<String, Object> consumerFor(String... topics) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                CommerceFlowContainers.kafka().getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        config.put(JsonDeserializer.TYPE_MAPPINGS, KafkaTypeMappings.mappingProperty());
        config.put(JsonDeserializer.TRUSTED_PACKAGES, "com.commerceflow.common.event");
        config.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, true);

        Consumer<String, Object> consumer =
                new DefaultKafkaConsumerFactory<String, Object>(config).createConsumer();
        consumer.subscribe(List.of(topics));
        // Force the assignment so the subscription is live before the test publishes anything.
        consumer.poll(Duration.ofMillis(500));
        consumer.seekToEnd(consumer.assignment());
        return consumer;
    }

    /** Drains whatever is available, up to {@code timeout}. */
    protected List<ConsumerRecord<String, Object>> drain(Consumer<String, Object> consumer,
                                                         Duration timeout) {
        List<ConsumerRecord<String, Object>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, Object> records = consumer.poll(Duration.ofMillis(300));
            records.forEach(collected::add);
            if (!collected.isEmpty()) {
                break;
            }
        }
        return collected;
    }

}

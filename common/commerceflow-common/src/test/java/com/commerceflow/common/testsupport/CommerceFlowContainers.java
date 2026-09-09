package com.commerceflow.common.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The infrastructure every integration test needs, started once per JVM.
 *
 * <p>Singleton containers rather than one set per test class: starting Postgres and Kafka costs
 * a few seconds each, and a suite that pays that per class stops being run. They are shut down
 * by Ryuk when the JVM exits.
 *
 * <p>Deliberately the same major versions as production (`technical-rules.md`): an integration
 * test against a different engine is testing a different system.
 */
public final class CommerceFlowContainers {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("postgres:16-alpine");
    private static final DockerImageName KAFKA_IMAGE =
            DockerImageName.parse("confluentinc/cp-kafka:7.7.1");
    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis:7-alpine");

    private static final PostgreSQLContainer<?> POSTGRES;
    private static final KafkaContainer KAFKA;
    private static final GenericContainer<?> REDIS;

    static {
        POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withDatabaseName("commerceflow_test")
                .withUsername("commerceflow")
                .withPassword("commerceflow")
                .withReuse(false);

        KAFKA = new KafkaContainer(KAFKA_IMAGE)
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
                .withEnv("KAFKA_NUM_PARTITIONS", "1")
                .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                .withReuse(false);

        REDIS = new GenericContainer<>(REDIS_IMAGE)
                .withExposedPorts(6379)
                .withReuse(false);

        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    private CommerceFlowContainers() {
        throw new AssertionError("No instances");
    }

    public static PostgreSQLContainer<?> postgres() {
        return POSTGRES;
    }

    public static KafkaContainer kafka() {
        return KAFKA;
    }

    public static GenericContainer<?> redis() {
        return REDIS;
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }
}

package com.commerceflow.common.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Tuning knobs for the transactional outbox relay. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.outbox")
public class OutboxProperties {

    /** Turns the relay and its scheduled jobs on or off. */
    private boolean enabled = true;

    /**
     * Turns only the polling half off, leaving {@code OutboxRelay} available as a bean.
     * Integration tests use this to drive the relay explicitly instead of racing the scheduler.
     */
    private boolean schedulerEnabled = true;

    /** Delay between two relay polls, in milliseconds. */
    private long pollIntervalMs = 1000L;

    /** Maximum number of rows claimed per poll. */
    private int batchSize = 100;

    /** Delivery attempts before a row is parked in {@code FAILED}. */
    private int maxAttempts = 10;

    /** How long to wait for the broker acknowledgement of a single record. */
    private Duration sendTimeout = Duration.ofSeconds(10);

    /** Deletes delivered rows older than this. */
    private Duration retention = Duration.ofDays(7);

    /** Delay between two retention sweeps, in milliseconds. Defaults to one hour. */
    private long cleanupIntervalMs = 3_600_000L;

    /** Turns the retention sweep on or off independently of the relay. */
    private boolean cleanupEnabled = true;
}

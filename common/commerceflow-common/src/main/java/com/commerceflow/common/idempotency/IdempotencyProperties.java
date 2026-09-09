package com.commerceflow.common.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Tuning knobs for the consumer idempotency ledger. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.idempotency")
public class IdempotencyProperties {

    /** Turns the ledger housekeeping job on or off. */
    private boolean cleanupEnabled = true;

    /**
     * How long a processed-event claim is kept. Must comfortably exceed the Kafka topic
     * retention, otherwise a very late redelivery could be processed twice.
     */
    private Duration retention = Duration.ofDays(7);

    /** Delay between two housekeeping sweeps, in milliseconds. Defaults to one hour. */
    private long cleanupIntervalMs = 3_600_000L;
}

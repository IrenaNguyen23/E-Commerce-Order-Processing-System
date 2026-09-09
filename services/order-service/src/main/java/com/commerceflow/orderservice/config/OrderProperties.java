package com.commerceflow.orderservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import lombok.Getter;
import lombok.Setter;

/** Order Service tuning knobs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.order")
public class OrderProperties {

    /** Prefix of the human readable order number, e.g. {@code CF-20260826-000123}. */
    private String numberPrefix = "CF";

    /** Upper bound on the number of lines in one order. */
    private int maxItemsPerOrder = 50;

    /**
     * Most units of one product on a single line.
     *
     * <p>A ceiling rather than a business rule. A basket is writable by whoever holds the token,
     * and a quantity field with no bound is an integer overflow waiting to be multiplied by a
     * price. Nobody buying honestly will meet it.
     */
    private int maxQuantityPerLine = 99;

    /**
     * How long an untouched basket is kept before the sweeper removes it.
     *
     * <p>Months, not hours, and the difference is worth being clear about: a stale basket holds no
     * stock and blocks nothing, so this is housekeeping rather than a correctness problem.
     * Deleting somebody's basket after a week would lose a genuine shopping list to save a few
     * kilobytes.
     */
    private Duration abandonedCartRetention = Duration.ofDays(120);

    /**
     * How long a finished saga's step log is kept.
     *
     * <p>Ninety days. The log answers "why did this take four attempts last Tuesday", which is a
     * question with a short life — nobody asks it about an order from two years ago. The
     * <b>order</b> is kept indefinitely and is what an accountant or a dispute actually needs;
     * this is only the saga's working notes.
     *
     * <p>A parked saga is never swept whatever this says, because its step log is the entire
     * record of what it was doing when it stopped.
     */
    private Duration sagaHistoryRetention = Duration.ofDays(90);

    /** How long a single order projection is cached in Redis. */
    private Duration readModelCacheTtl = Duration.ofMinutes(2);

    @NestedConfigurationProperty
    private Saga saga = new Saga();

    /**
     * Orchestrator settings.
     *
     * <p>Every step has a deadline and a retry budget, and both are configuration rather than
     * folklore. Tune the timeout to the slowest participant's honest worst case: it is the point
     * at which a command is re-sent, not the point at which anything is given up on.
     */
    @Getter
    @Setter
    public static class Saga {

        /**
         * How long a participant has to answer a command before it is chased.
         *
         * <p>Generous on purpose. It is the point at which a command is re-sent, not the point at
         * which anything is given up on, and re-sending too eagerly turns a slow participant into
         * a busy one.
         */
        private Duration stepTimeout = Duration.ofMinutes(2);

        /**
         * How many times one step's command is sent before the saga is parked.
         *
         * <p>Deliberately small. If two extra attempts have not produced a reply, the participant
         * is not slow — it is down, and a third command will not fix that.
         */
        private int maxAttempts = 3;

        /** Delay between two scans for overdue steps, in milliseconds. */
        private long scanIntervalMs = 30_000L;

        /**
         * Most sagas one scan will chase.
         *
         * <p>Bounds the work done in a single pass so a broker outage — exactly when the overdue
         * count is largest — cannot turn the scanner into the next problem.
         */
        private int scanBatchSize = 100;
    }
}

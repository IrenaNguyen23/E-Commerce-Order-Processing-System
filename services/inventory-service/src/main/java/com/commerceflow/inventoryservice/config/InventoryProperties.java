package com.commerceflow.inventoryservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import lombok.Getter;
import lombok.Setter;

/** Inventory Service tuning knobs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.inventory")
public class InventoryProperties {

    @NestedConfigurationProperty
    private Reservation reservation = new Reservation();

    /**
     * Largest product image accepted, in bytes.
     *
     * <p>Two megabytes is a generous product photograph and a poor denial-of-service payload. The
     * limit is enforced before the file is inspected, so a rejected upload costs the size check
     * and nothing else.
     *
     * <p>Note that Spring's own multipart limits sit in front of this
     * ({@code spring.servlet.multipart.max-file-size}); both have to allow a file through, and the
     * message a customer sees is better when this one is the stricter of the two.
     */
    private long maxImageBytes = 2L * 1024 * 1024;

    /** How many images one product may carry. A gallery, not a photo library. */
    private int maxImagesPerProduct = 8;

    @NestedConfigurationProperty
    private Reviews reviews = new Reviews();

    /** Review policy. */
    @Getter
    @Setter
    public static class Reviews {

        /**
         * Publish new reviews immediately instead of holding them for moderation.
         *
         * <p>Off by default. A product page is somewhere anybody with an account can publish
         * arbitrary text under the shop's name; publishing on submission means the first abusive
         * or fraudulent review is live until a human notices. Holding them means the worst case
         * is a delay.
         *
         * <p>The trade-off is a policy rather than a law, which is why it is a switch — but the
         * default is the cautious one, because the cost of being wrong is asymmetric.
         */
        private boolean autoPublish = false;

        /**
         * Only let customers review what they have bought.
         *
         * <p>Off by default because it is a stricter shop than most run, and because a customer
         * who bought elsewhere and has an opinion is not lying. When it is on, the check is a
         * local lookup against purchases recorded from {@code order.completed} — never a call
         * into Order Service.
         */
        private boolean requirePurchase = false;
    }

    /** Stock-hold settings. */
    @Getter
    @Setter
    public static class Reservation {

        /**
         * How old a hold has to be before it is reported as stale.
         *
         * <p>Generous on purpose. A healthy saga reserves and settles in seconds, and even one
         * that has to be chased is finished within minutes. Anything still holding stock hours
         * later is not slow — something has gone wrong with it, and the number here only decides
         * how long to wait before saying so.
         */
        private Duration staleAfter = Duration.ofHours(2);

        /** Delay between two sweeps for stale holds, in milliseconds. */
        private long scanIntervalMs = 600_000L;

        /** Most holds named in one report, so a bad day cannot produce an unreadable log line. */
        private int scanBatchSize = 200;
    }
}

package com.commerceflow.common.audit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** How long operator actions are kept, and whether the sweeper runs at all. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.audit")
public class AuditProperties {

    /**
     * How long an entry is kept.
     *
     * <p>Two years by default, which is longer than anything else this platform retains and
     * deliberately so. The idempotency ledger exists to stop a message being processed twice and
     * is useless after a day; an audit trail exists to answer a question somebody asks after an
     * incident, a dispute or an audit, and those arrive late.
     *
     * <p>Anyone with a statutory retention period should set this from it rather than accepting a
     * default chosen by a developer.
     *
     * <p><b>Do not set this below 30 days.</b> A database trigger refuses to delete an entry
     * younger than that — it is what stops an operator tidying away the record of what they did
     * this morning — so a shorter retention makes the nightly sweep fail against it every night.
     * An audit trail kept for less than a month would be an odd choice regardless.
     */
    private Duration retention = Duration.ofDays(730);

    /** Cron for the sweep. Nightly, off-peak — nothing here is urgent. */
    private String cleanupCron = "0 30 3 * * *";

    /**
     * Turns the sweeper off entirely.
     *
     * <p>Worth having as a switch: a deployment under a legal hold must not delete anything, and
     * the way to guarantee that is to stop the job rather than to set a very large retention and
     * hope.
     */
    private boolean cleanupEnabled = true;
}

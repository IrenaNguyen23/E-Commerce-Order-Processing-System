package com.commerceflow.common.audit;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes audit entries past their retention.
 *
 * <p>Its own job with its own number, rather than a line in a general housekeeping sweep. How long
 * an audit trail is kept is a compliance decision; how long an idempotency ledger is kept is a
 * disk-space one. Sharing a schedule invites sharing a number, and then somebody shortens the
 * wrong one.
 *
 * <p>Deletes rather than reports, unlike the stale-reservation monitor — an entry past its
 * retention is one the organisation has decided not to keep, which is a decision already made
 * rather than a judgement call at the moment of sweeping.
 */
public class AuditSweeper {

    private static final Logger log = LoggerFactory.getLogger(AuditSweeper.class);

    private final AuditRepository repository;
    private final AuditProperties properties;

    public AuditSweeper(AuditRepository repository, AuditProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(cron = "${commerceflow.audit.cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void sweep() {
        Instant cutoff = Instant.now().minus(properties.getRetention());
        int removed = repository.deleteOlderThan(cutoff);

        if (removed > 0) {
            // At info even though it is routine: an audit trail shrinking is itself something an
            // auditor may ask about, and this line is the answer.
            log.info("Removed {} audit entr{} older than {}", removed,
                    removed == 1 ? "y" : "ies", cutoff);
        }
    }
}

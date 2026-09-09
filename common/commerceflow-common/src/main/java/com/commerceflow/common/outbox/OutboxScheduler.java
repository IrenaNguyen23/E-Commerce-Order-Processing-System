package com.commerceflow.common.outbox;

import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Drives {@link OutboxRelay} on a fixed delay.
 *
 * <p>Kept separate from the relay so the relay's transactional methods stay unit testable and
 * can be invoked on demand from an integration test without any scheduling involved.
 */
@Slf4j
@RequiredArgsConstructor
public class OutboxScheduler {

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    @Scheduled(
            fixedDelayString = "${commerceflow.outbox.poll-interval-ms:1000}",
            initialDelayString = "${commerceflow.outbox.poll-interval-ms:1000}")
    public void relayPendingEvents() {
        try {
            relay.publishPendingBatch();
        } catch (Exception ex) {
            log.error("Outbox relay poll failed", ex);
        }
    }

    @Scheduled(
            fixedDelayString = "${commerceflow.outbox.cleanup-interval-ms:3600000}",
            initialDelayString = "${commerceflow.outbox.cleanup-interval-ms:3600000}")
    public void purgePublishedEvents() {
        if (!properties.isCleanupEnabled()) {
            return;
        }
        try {
            relay.purgePublished();
        } catch (Exception ex) {
            log.error("Outbox retention sweep failed", ex);
        }
    }
}

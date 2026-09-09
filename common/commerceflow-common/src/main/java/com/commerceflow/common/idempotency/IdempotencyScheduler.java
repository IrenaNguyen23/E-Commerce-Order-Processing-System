package com.commerceflow.common.idempotency;

import org.springframework.scheduling.annotation.Scheduled;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the idempotency ledger bounded.
 *
 * <p>The ledger only has to cover the broker retention window: once Kafka can no longer redeliver
 * a record, remembering that it was processed has no value.
 */
@Slf4j
@RequiredArgsConstructor
public class IdempotencyScheduler {

    private final IdempotencyService idempotencyService;
    private final IdempotencyProperties properties;

    @Scheduled(
            fixedDelayString = "${commerceflow.idempotency.cleanup-interval-ms:3600000}",
            initialDelayString = "${commerceflow.idempotency.cleanup-interval-ms:3600000}")
    public void purgeExpiredClaims() {
        try {
            idempotencyService.purgeOlderThan(properties.getRetention());
        } catch (Exception ex) {
            log.error("Idempotency ledger sweep failed", ex);
        }
    }
}

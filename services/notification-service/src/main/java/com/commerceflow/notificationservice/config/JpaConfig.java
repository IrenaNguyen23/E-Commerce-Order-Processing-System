package com.commerceflow.notificationservice.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Widens entity and repository scanning to the shared infrastructure packages.
 *
 * <p>Notification consumes saga commands and replies to them, so it opts into the outbox and the idempotency ledger.
 *
 * <p>Every service opts into {@code common.audit}: an operator action is recorded in the same
 * database as the thing it changed, for the same reason the outbox is local — a shared audit
 * table would be one row every service writes synchronously, and therefore one thing whose
 * availability the whole platform depends on for an operation that must never fail.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackages = {
        "com.commerceflow.notificationservice.entity",
        "com.commerceflow.common.outbox",
        "com.commerceflow.common.idempotency",
        "com.commerceflow.common.audit"
})
@EnableJpaRepositories(basePackages = {
        "com.commerceflow.notificationservice.repository",
        "com.commerceflow.common.outbox",
        "com.commerceflow.common.idempotency",
        "com.commerceflow.common.audit"
})
public class JpaConfig {
}

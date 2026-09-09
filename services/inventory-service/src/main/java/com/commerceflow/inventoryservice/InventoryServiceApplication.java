package com.commerceflow.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Inventory Service — product catalogue and stock ownership.
 *
 * <p>Saga participant: carries out the reserve, release and confirm commands the orchestrator
 * sends on {@code inventory.commands}, and answers every one of them.
 *
 * <p>{@code @EnableScheduling} is declared here rather than inherited from the outbox
 * auto-configuration: the stale-reservation sweep must keep running even if the outbox relay is
 * ever switched off, and a housekeeping job that stops silently is worse than one that is not
 * there at all.
 */
@EnableCaching
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}

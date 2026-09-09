package com.commerceflow.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order Service — the saga initiator and the system of record for orders.
 *
 * <p>Command side writes the {@code orders} aggregate and the outbox in one transaction; the
 * query side is served from a denormalised read model kept up to date by the same handlers
 * (CQRS), so a customer-facing order list never has to join across the write tables.
 */
@EnableCaching
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

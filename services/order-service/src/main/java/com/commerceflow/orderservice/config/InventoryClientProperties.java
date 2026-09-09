package com.commerceflow.orderservice.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Connection settings for the synchronous catalogue read. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.client.inventory")
public class InventoryClientProperties {

    /** Base URL of Inventory Service; static DNS, no service registry (ADR-003). */
    private String baseUrl = "http://localhost:8083";

    /** Kept short: a customer waiting to place an order must not wait on a hung dependency. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(3);
}

package com.commerceflow.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * API Gateway — the single entry point of the platform (ADR-002).
 *
 * <p>Terminates client traffic, verifies the access token once, applies Redis backed rate limits
 * and circuit breakers, and forwards to the services over static DNS (ADR-003: no registry).
 *
 * <p>Reactive by design: the gateway is almost entirely I/O bound, so a small event loop handles
 * far more concurrent connections than a thread-per-request model would.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}

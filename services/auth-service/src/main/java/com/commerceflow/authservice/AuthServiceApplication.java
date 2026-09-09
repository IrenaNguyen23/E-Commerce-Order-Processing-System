package com.commerceflow.authservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Auth Service — the identity provider of the platform.
 *
 * <p>Owns the {@code commerceflow_auth} database (ADR-005), issues the JWT access and refresh
 * tokens every other service verifies (ADR-001), and publishes a welcome notification through the
 * transactional outbox (ADR-006).
 */
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthServiceApplication.class, args);
    }
}

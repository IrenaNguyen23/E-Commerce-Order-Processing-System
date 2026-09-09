package com.commerceflow.inventoryservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Demo catalogue seeding, disabled by default.
 *
 * <p>Off unless a profile turns it on, so a deployed environment never has demo products
 * appear in its catalogue. {@code docker-compose.yml} enables it; the Kubernetes ConfigMap
 * deliberately does not.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.bootstrap.demo-catalogue")
public class DemoCatalogueProperties {

    /** Seeds the demo products on start-up when true. */
    private boolean enabled;
}

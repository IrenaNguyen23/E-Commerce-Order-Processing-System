package com.commerceflow.gateway.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Edge configuration that is not already expressed as a Spring Cloud Gateway route. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.gateway")
public class GatewayProperties {

    /**
     * CORS origin patterns. Wide open by default so the stack is usable straight after
     * {@code docker compose up}; narrow it to the real front-end origins per environment.
     */
    private List<String> allowedOrigins = List.of("*");
}

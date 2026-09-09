package com.commerceflow.common.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** Configuration for issuing and verifying JWTs, shared by every module. */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "commerceflow.security.jwt")
public class JwtProperties {

    /**
     * HMAC signing secret. Must be at least 32 bytes for HS256; injected from a Kubernetes
     * secret or the {@code JWT_SECRET} environment variable — never hard coded in an image.
     */
    @NotBlank
    private String secret;

    /** Value written to, and required in, the {@code iss} claim. */
    private String issuer = "commerceflow";

    /** Lifetime of an access token. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** Lifetime of a refresh token. */
    private Duration refreshTokenTtl = Duration.ofDays(7);

    /** Leeway applied when validating {@code exp} and {@code nbf}. */
    private Duration clockSkew = Duration.ofSeconds(30);
}

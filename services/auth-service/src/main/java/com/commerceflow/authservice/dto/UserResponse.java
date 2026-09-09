package com.commerceflow.authservice.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

/** Public projection of an account. Never carries the password hash. */
@Schema(description = "Account")
public record UserResponse(
        UUID id,
        String email,
        String fullName,
        String phone,
        Set<String> roles,
        boolean enabled,
        Instant createdAt) {
}

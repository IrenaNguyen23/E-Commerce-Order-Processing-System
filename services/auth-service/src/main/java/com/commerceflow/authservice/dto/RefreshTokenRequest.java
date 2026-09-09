package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Payload of {@code POST /api/auth/refresh}. */
@Schema(description = "Refresh token exchange")
public record RefreshTokenRequest(

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "refreshToken is required")
        String refreshToken) {
}

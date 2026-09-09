package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/auth/verify-email}. */
@Schema(description = "Confirm an email address")
public record VerifyEmailRequest(

        @Schema(description = "The token from the emailed link",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "token is required")
        @Size(max = 128)
        String token) {
}

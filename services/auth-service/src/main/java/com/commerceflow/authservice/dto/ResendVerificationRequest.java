package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/auth/resend-verification}. */
@Schema(description = "Ask for another confirmation link")
public record ResendVerificationRequest(

        @Schema(example = "ada@commerceflow.io", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255)
        String email) {
}

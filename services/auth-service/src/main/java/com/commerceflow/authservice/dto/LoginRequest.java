package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/auth/login}. */
@Schema(description = "Credentials")
public record LoginRequest(

        @Schema(example = "ada@commerceflow.io", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        String email,

        @Schema(example = "S3cret-pass", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "password is required")
        @Size(max = 72)
        String password) {
}

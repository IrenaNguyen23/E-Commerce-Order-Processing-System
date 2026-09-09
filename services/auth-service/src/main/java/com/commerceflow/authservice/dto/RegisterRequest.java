package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Payload of {@code POST /api/auth/register}. */
@Schema(description = "New account registration")
public record RegisterRequest(

        @Schema(example = "ada@commerceflow.io", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "email is required")
        @Email(message = "email must be a valid address")
        @Size(max = 255)
        String email,

        @Schema(description = "At least 8 characters with one letter and one digit",
                example = "S3cret-pass", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "password is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "password must contain a letter")
        @Pattern(regexp = ".*[0-9].*", message = "password must contain a digit")
        String password,

        @Schema(example = "Ada Lovelace", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "fullName is required")
        @Size(max = 150)
        String fullName,

        @Schema(example = "+31612345678")
        @Size(max = 32)
        @Pattern(regexp = "^$|^[+]?[0-9 ()-]{6,32}$",
                message = "phone must be a valid phone number")
        String phone) {
}

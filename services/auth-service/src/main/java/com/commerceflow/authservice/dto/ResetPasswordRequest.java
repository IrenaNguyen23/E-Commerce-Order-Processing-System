package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code POST /api/auth/reset-password}.
 *
 * <p>The password rules are identical to registration on purpose. A reset that accepted a weaker
 * password than sign-up would be the easiest way to downgrade an account's security, and a
 * customer who is told "at least 8 characters with a letter and a digit" in one place and
 * something else in another stops believing either message.
 */
@Schema(description = "Complete a password reset")
public record ResetPasswordRequest(

        @Schema(description = "The token from the emailed link",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "token is required")
        @Size(max = 128)
        String token,

        @Schema(description = "At least 8 characters with one letter and one digit",
                example = "N3w-pass-2026", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "newPassword is required")
        @Size(min = 8, max = 72, message = "password must be between 8 and 72 characters")
        @Pattern(regexp = ".*[A-Za-z].*", message = "password must contain a letter")
        @Pattern(regexp = ".*[0-9].*", message = "password must contain a digit")
        String newPassword) {
}

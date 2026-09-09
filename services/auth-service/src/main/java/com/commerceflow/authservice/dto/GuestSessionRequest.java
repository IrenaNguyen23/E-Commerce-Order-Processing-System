package com.commerceflow.authservice.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Starting a checkout without an account.
 *
 * <p>An address and, optionally, a name. Nothing else is asked for, because everything else the
 * order needs is on the checkout form already and a guest checkout that collects a profile is not
 * a guest checkout.
 */
@Schema(description = "A guest checkout session")
public record GuestSessionRequest(

        @Schema(example = "ada@example.com", requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Where the order confirmation goes. If this address already has an "
                        + "account the request is refused — this endpoint hands out a session in "
                        + "exchange for an email address, so reusing an existing account would "
                        + "mean anyone could type your address and be signed in as you.")
        @NotBlank(message = "email is required")
        @Email(message = "That does not look like an email address")
        @Size(max = 255)
        String email,

        @Schema(example = "Ada Lovelace", description = "Optional. Used as the account name.")
        @Size(max = 150)
        String fullName) {
}

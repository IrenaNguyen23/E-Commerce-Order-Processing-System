package com.commerceflow.authservice.dto;

import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Payload of {@code PATCH /api/users/{id}}.
 *
 * <p>Both fields are optional: an operator disabling an account should not have to restate its
 * roles, and one granting a role should not have to restate whether it is enabled. A null field
 * means "leave this alone", which is the difference between PATCH and PUT and the reason this is
 * a PATCH.
 *
 * <p>There is no password field. An administrator cannot set someone else's password — that is
 * what the reset flow is for, and it keeps the property that only the account holder ever chooses
 * their own credential.
 */
@Schema(description = "Changes to an account. Omitted fields are left as they are.")
public record UpdateUserRequest(

        @Schema(description = "False signs the account out everywhere and blocks sign-in.")
        Boolean enabled,

        @Schema(description = "Replaces the account's roles entirely.", example = "[\"CUSTOMER\"]")
        // Size, not NotEmpty: null means "leave the roles alone", while an empty set
        // would strip every permission and leave an account that can do nothing.
        @Size(min = 1, message = "roles cannot be empty; an account with no role can do nothing")
        Set<String> roles) {
}

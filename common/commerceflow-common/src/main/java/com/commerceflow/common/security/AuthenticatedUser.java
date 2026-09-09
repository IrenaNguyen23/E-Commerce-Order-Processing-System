package com.commerceflow.common.security;

import java.security.Principal;
import java.util.Set;
import java.util.UUID;

/**
 * The verified identity behind the current request.
 *
 * @param userId  subject of the token
 * @param email   customer email
 * @param roles   granted roles, without the {@code ROLE_} prefix
 * @param tokenId {@code jti} claim, used for logout / revocation checks
 */
public record AuthenticatedUser(UUID userId, String email, Set<String> roles, String tokenId)
        implements Principal {

    @Override
    public String getName() {
        return email;
    }

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public boolean isAdmin() {
        return hasRole(SecurityHeaders.ROLE_ADMIN);
    }
}

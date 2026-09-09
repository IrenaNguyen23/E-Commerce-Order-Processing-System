package com.commerceflow.common.security;

/**
 * Strategy for checking whether an otherwise valid access token has been revoked (logout).
 *
 * <p>Auth Service backs this with a Redis blacklist keyed on the {@code jti} claim; the other
 * services use {@link #alwaysActive()} because they cannot see the blacklist and the token TTL
 * is short by design.
 */
@FunctionalInterface
public interface TokenRevocationChecker {

    boolean isRevoked(String tokenId);

    /** No-op implementation: nothing is revoked. */
    static TokenRevocationChecker alwaysActive() {
        return tokenId -> false;
    }
}

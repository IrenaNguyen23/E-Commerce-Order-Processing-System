package com.commerceflow.authservice.entity;

import java.time.Duration;

/**
 * What a verification token grants.
 *
 * <p>Each purpose carries its own lifetime, because the two are not the same kind of risk. A
 * password reset token is a temporary key to an account: the shorter it lives, the smaller the
 * window in which a leaked mailbox becomes a compromised account. A verification link only proves
 * an address is reachable, so it can afford to survive a weekend.
 */
public enum TokenPurpose {

    /** Proves the address can receive mail. Long lived: nothing is at stake if it leaks. */
    EMAIL_VERIFICATION(Duration.ofDays(2)),

    /** Grants a password change. Deliberately short: it is a key to the account. */
    PASSWORD_RESET(Duration.ofMinutes(30));

    private final Duration lifetime;

    TokenPurpose(Duration lifetime) {
        this.lifetime = lifetime;
    }

    public Duration lifetime() {
        return lifetime;
    }
}

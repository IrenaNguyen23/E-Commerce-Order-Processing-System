package com.commerceflow.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Auth Service tuning knobs. */
@Getter
@Setter
@ConfigurationProperties(prefix = "commerceflow.auth")
public class AuthProperties {

    /**
     * Where the storefront lives, for links emailed to customers.
     *
     * <p>Not the gateway. A password reset link has to land on a page that can take a new
     * password, and the moment the frontend is deployed on its own host — Vercel, a CDN, anywhere
     * — that is a different origin from the API. Getting this wrong sends every customer a link
     * to a 404.
     */
    private String webBaseUrl = "http://localhost:3001";

    /**
     * Refuses sign-in until the address has been verified.
     *
     * <p>Off by default, and that default is a demo convenience rather than a recommendation:
     * {@code docker compose up} has to give a working system to someone with no mail server, and
     * with this on and no SMTP configured nobody could ever sign in.
     *
     * <p><b>Turn it on in production.</b> Off, anyone can register with someone else's address
     * and start receiving their order confirmations.
     */
    private boolean requireVerifiedEmail = false;

    /** How long a consumed or expired verification token is kept before the sweep drops it. */
    private java.time.Duration verificationTokenRetention = java.time.Duration.ofDays(7);

    /**
     * How long past its expiry a refresh token is kept before the sweep drops it.
     *
     * <p>A grace period rather than zero, so a support question asked the next morning still has
     * a row to look at. Revoked-but-unexpired tokens are never touched by the sweep: they are
     * what makes a sign-out stick.
     */
    private java.time.Duration refreshTokenRetention = java.time.Duration.ofDays(7);

    /**
     * Wrong passwords before an account stops accepting attempts.
     *
     * <p>Five, which is generous for a person and expensive for a script. The failures do not have
     * to be consecutive — a counter that reset on every success would let somebody alternate a
     * guess with a known-good sign-in on their own account and never trip it.
     */
    private int maxLoginFailures = 5;

    /**
     * How long the failure counter survives.
     *
     * <p>Longer than the lock, deliberately. Without a window, somebody could sit one attempt
     * below the threshold forever: the count would expire between tries and the lock would never
     * fire.
     */
    private java.time.Duration loginFailureWindow = java.time.Duration.ofMinutes(30);

    /**
     * How long an account refuses attempts once locked.
     *
     * <p>Fifteen minutes: long enough to make a guessing attack pointless, short enough that
     * somebody who mistyped their own password is not reading a support article. It expires on its
     * own — nobody has to remember to unlock it.
     */
    private java.time.Duration loginLockDuration = java.time.Duration.ofMinutes(15);

    /**
     * How many addresses one customer may keep.
     *
     * <p>Not a business rule — a ceiling. A profile is writable by whoever holds the token, and an
     * unbounded list is unbounded storage. Twenty is far past what a person needs and far below
     * what an abusive client would want.
     */
    private int maxAddressesPerUser = 20;
}

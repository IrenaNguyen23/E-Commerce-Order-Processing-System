package com.commerceflow.common.security;

/**
 * Headers the API Gateway injects after it has validated the access token.
 *
 * <p>Downstream services still validate the JWT themselves (defence in depth); these headers
 * are only a convenience for logging and for internal service-to-service calls.
 */
public final class SecurityHeaders {

    public static final String AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    public static final String USER_ID = "X-User-Id";
    public static final String USER_EMAIL = "X-User-Email";
    public static final String USER_ROLES = "X-User-Roles";

    /** Role granted to ordinary customers. */
    public static final String ROLE_CUSTOMER = "CUSTOMER";

    /** Role granted to back-office operators. */
    public static final String ROLE_ADMIN = "ADMIN";

    private SecurityHeaders() {
        throw new AssertionError("No instances");
    }
}

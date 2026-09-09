package com.commerceflow.common.exception;

/**
 * Catalogue of machine readable error codes returned in {@code ErrorResponse.code}.
 *
 * <p>The HTTP status is kept as a plain {@code int} so this enum carries no dependency on
 * {@code spring-web} and can be reused by non-servlet modules.
 */
public enum ErrorCode {

    // ---- generic -------------------------------------------------------------------------
    VALIDATION_FAILED(400, "Request validation failed"),
    BAD_REQUEST(400, "Bad request"),
    UNAUTHORIZED(401, "Authentication is required"),
    INVALID_CREDENTIALS(401, "Invalid email or password"),
    TOKEN_EXPIRED(401, "Token has expired"),
    TOKEN_INVALID(401, "Token is invalid"),
    FORBIDDEN(403, "Access is denied"),
    RESOURCE_NOT_FOUND(404, "Resource not found"),
    CONFLICT(409, "Request conflicts with the current state of the resource"),
    IDEMPOTENCY_CONFLICT(409, "A different request with the same idempotency key already exists"),
    UNPROCESSABLE(422, "Request could not be processed"),
    RATE_LIMITED(429, "Too many requests"),
    INTERNAL_ERROR(500, "Unexpected internal error"),
    SERVICE_UNAVAILABLE(503, "Downstream service is unavailable"),

    // ---- auth ----------------------------------------------------------------------------
    EMAIL_ALREADY_REGISTERED(409, "Email is already registered"),
    ACCOUNT_DISABLED(403, "Account is disabled"),
    EMAIL_NOT_VERIFIED(403, "Email address has not been verified"),
    REFRESH_TOKEN_INVALID(401, "Refresh token is invalid or has been revoked"),

    // ---- catalogue / inventory -----------------------------------------------------------
    PRODUCT_NOT_FOUND(404, "Product not found"),
    SKU_ALREADY_EXISTS(409, "SKU already exists"),
    INSUFFICIENT_STOCK(409, "Insufficient stock for the requested quantity"),
    RESERVATION_NOT_FOUND(404, "Inventory reservation not found"),

    // ---- order ---------------------------------------------------------------------------
    ORDER_NOT_FOUND(404, "Order not found"),
    ORDER_NOT_MODIFIABLE(409, "Order can no longer be modified"),
    EMPTY_ORDER(400, "An order must contain at least one item"),

    // ---- payment -------------------------------------------------------------------------
    PAYMENT_NOT_FOUND(404, "Payment not found"),
    PAYMENT_ALREADY_PROCESSED(409, "Payment for this order has already been processed"),
    PAYMENT_DECLINED(402, "Payment was declined"),

    // ---- notification --------------------------------------------------------------------
    NOTIFICATION_NOT_FOUND(404, "Notification not found");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}

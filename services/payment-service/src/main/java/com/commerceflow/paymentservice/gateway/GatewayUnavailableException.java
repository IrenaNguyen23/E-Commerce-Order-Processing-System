package com.commerceflow.paymentservice.gateway;

/**
 * The acquirer could not be reached, or answered in a way that says nothing about the charge.
 *
 * <p>Distinct from a decline on purpose. A decline is an answer — record it, tell the saga, move
 * on. This is the absence of an answer, and recording it as a decline would cancel an order that
 * may well have been paid for. Letting it propagate means the listener retries and, failing that,
 * dead-letters, which keeps the question open until someone can answer it.
 */
public class GatewayUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GatewayUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}

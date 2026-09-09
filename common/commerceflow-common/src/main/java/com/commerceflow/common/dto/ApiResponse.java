package com.commerceflow.common.dto;

import java.time.Instant;

import com.commerceflow.common.context.CorrelationContext;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uniform success envelope returned by every REST endpoint of the platform.
 *
 * @param <T> payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp,
        String correlationId) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now(), CorrelationContext.get());
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, data, message, Instant.now(), CorrelationContext.get());
    }

    public static ApiResponse<Void> message(String message) {
        return new ApiResponse<>(true, null, message, Instant.now(), CorrelationContext.get());
    }
}

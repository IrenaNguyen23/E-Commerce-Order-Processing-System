package com.commerceflow.common.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Uniform error envelope returned by every non-2xx response of the platform. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        boolean success,
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String correlationId,
        List<FieldError> fieldErrors) {

    /** A single failed bean-validation constraint. */
    public record FieldError(String field, Object rejectedValue, String message) {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Small hand written builder: keeps the record immutable without pulling in Lombok. */
    public static final class Builder {
        private Instant timestamp = Instant.now();
        private int status;
        private String error;
        private String code;
        private String message;
        private String path;
        private String correlationId;
        private List<FieldError> fieldErrors;

        public Builder timestamp(Instant value) {
            this.timestamp = value;
            return this;
        }

        public Builder status(int value) {
            this.status = value;
            return this;
        }

        public Builder error(String value) {
            this.error = value;
            return this;
        }

        public Builder code(String value) {
            this.code = value;
            return this;
        }

        public Builder message(String value) {
            this.message = value;
            return this;
        }

        public Builder path(String value) {
            this.path = value;
            return this;
        }

        public Builder correlationId(String value) {
            this.correlationId = value;
            return this;
        }

        public Builder fieldErrors(List<FieldError> value) {
            this.fieldErrors = value == null || value.isEmpty() ? null : List.copyOf(value);
            return this;
        }

        public ErrorResponse build() {
            return new ErrorResponse(
                    false, timestamp, status, error, code, message, path, correlationId, fieldErrors);
        }
    }
}

package com.commerceflow.common.exception;

import java.io.Serial;

/** Thrown when a request conflicts with the current state of a resource. Maps to HTTP 409. */
public class ConflictException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConflictException(ErrorCode errorCode) {
        super(errorCode);
    }

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}

package com.commerceflow.common.exception;

import java.io.Serial;

/** Thrown when authentication is missing or invalid. Maps to HTTP 401. */
public class UnauthorizedException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public UnauthorizedException(ErrorCode errorCode) {
        super(errorCode);
    }

    public UnauthorizedException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}

package com.commerceflow.common.exception;

import java.io.Serial;

/**
 * Base class for every expected, domain level failure.
 *
 * <p>Carries an {@link ErrorCode} so {@code GlobalExceptionHandler} can translate it into the
 * right HTTP status without any per-service mapping table.
 */
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.defaultMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}

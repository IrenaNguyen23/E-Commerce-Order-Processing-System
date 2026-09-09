package com.commerceflow.common.exception;

import java.io.Serial;

/** Thrown when an addressable resource does not exist. Maps to HTTP 404. */
public class ResourceNotFoundException extends BusinessException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResourceNotFoundException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public ResourceNotFoundException(String resource, Object id) {
        super(ErrorCode.RESOURCE_NOT_FOUND, resource + " not found: " + id);
    }
}

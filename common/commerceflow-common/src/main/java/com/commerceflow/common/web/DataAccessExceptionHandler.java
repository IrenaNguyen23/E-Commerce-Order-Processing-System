package com.commerceflow.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.commerceflow.common.dto.ErrorResponse;
import com.commerceflow.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Turns persistence level conflicts into a 409 instead of a 500.
 *
 * <p>Registered only when Spring's DAO exception hierarchy is on the classpath.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RestControllerAdvice
public class DataAccessExceptionHandler {

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrityViolation(DataIntegrityViolationException ex,
                                                                  HttpServletRequest request) {
        log.warn("Data integrity violation on {} {}: {}", request.getMethod(),
                request.getRequestURI(), ex.getMostSpecificCause().getMessage());
        return GlobalExceptionHandler.build(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "The request conflicts with existing data", request, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex,
                                                              HttpServletRequest request) {
        log.warn("Optimistic lock lost on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return GlobalExceptionHandler.build(HttpStatus.CONFLICT, ErrorCode.CONFLICT,
                "The resource was modified concurrently, please retry", request, null);
    }
}

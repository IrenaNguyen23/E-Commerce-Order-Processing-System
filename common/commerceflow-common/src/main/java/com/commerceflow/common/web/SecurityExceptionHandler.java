package com.commerceflow.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.commerceflow.common.dto.ErrorResponse;
import com.commerceflow.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * Maps Spring Security failures raised inside the MVC dispatch (for example by
 * {@code @PreAuthorize}) onto the platform error envelope.
 *
 * <p>Registered only when Spring Security is on the classpath.
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RestControllerAdvice
public class SecurityExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                            HttpServletRequest request) {
        log.warn("Access denied on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return GlobalExceptionHandler.build(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                ErrorCode.FORBIDDEN.defaultMessage(), request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        log.warn("Authentication failed on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return GlobalExceptionHandler.build(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED.defaultMessage(), request, null);
    }
}

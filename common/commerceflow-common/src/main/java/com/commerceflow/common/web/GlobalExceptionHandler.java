package com.commerceflow.common.web;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.commerceflow.common.context.CorrelationContext;
import com.commerceflow.common.dto.ErrorResponse;
import com.commerceflow.common.exception.BusinessException;
import com.commerceflow.common.exception.ErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

/**
 * Translates every exception escaping a controller into the platform's {@link ErrorResponse}.
 *
 * <p>Expected failures are logged at {@code WARN} without a stack trace; unexpected ones at
 * {@code ERROR} with the full trace and a correlation id the caller can quote.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode code = ex.getErrorCode();
        log.warn("Business rule violated [{}] on {} {}: {}",
                code, request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.valueOf(code.httpStatus()), code, ex.getMessage(), request, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBeanValidation(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> fieldErrors.add(
                new ErrorResponse.FieldError(
                        error.getField(), error.getRejectedValue(), error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(error -> fieldErrors.add(
                new ErrorResponse.FieldError(error.getObjectName(), null, error.getDefaultMessage())));

        log.warn("Validation failed on {} {}: {} field error(s)",
                request.getMethod(), request.getRequestURI(), fieldErrors.size());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), request, fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.add(new ErrorResponse.FieldError(
                    String.valueOf(violation.getPropertyPath()),
                    violation.getInvalidValue(),
                    violation.getMessage()));
        }
        log.warn("Constraint violation on {} {}", request.getMethod(), request.getRequestURI());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), request, fieldErrors);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleMalformedRequest(Exception ex, HttpServletRequest request) {
        log.warn("Malformed request on {} {}: {}",
                request.getMethod(), request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, ErrorCode.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                          HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND,
                "No endpoint " + request.getMethod() + " " + request.getRequestURI(), request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = CorrelationContext.getOrCreate();
        log.error("Unhandled exception [{}] on {} {}",
                correlationId, request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage(), request, null);
    }

    static ResponseEntity<ErrorResponse> build(HttpStatus status, ErrorCode code, String message,
                                               HttpServletRequest request,
                                               List<ErrorResponse.FieldError> fieldErrors) {
        ErrorResponse body = ErrorResponse.builder()
                .status(status.value())
                .error(status.getReasonPhrase())
                .code(code.name())
                .message(message == null ? code.defaultMessage() : message)
                .path(request == null ? null : request.getRequestURI())
                .correlationId(CorrelationContext.get())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(status).body(body);
    }
}

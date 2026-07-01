package com.positivity.mcp.internal.controller;

import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ToolPermissionController.class)
class ToolPermissionExceptionHandler {

    private final Clock clock;

    ToolPermissionExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.withFieldErrors(
                        "VALIDATION_ERROR",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString(),
                        fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<ApiError> handleNotFound(NoSuchElementException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "TOOL_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }
}

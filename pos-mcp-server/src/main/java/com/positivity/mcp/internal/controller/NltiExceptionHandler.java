package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.InvalidDocumentMetadataException;
import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.exception.WritePlanConflictException;
import com.positivity.mcp.internal.exception.WritePlanExecutionException;
import com.positivity.mcp.internal.exception.WritePlanExpiredException;
import com.positivity.mcp.internal.exception.WritePlanNotFoundException;
import com.positivity.mcp.internal.exception.WritePlanStaleException;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
        assignableTypes = {
            NltiController.class,
            McpChatController.class,
            McpStreamingChatController.class,
            DocumentIngestionController.class
        })
class NltiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(NltiExceptionHandler.class);

    private final Clock clock;

    NltiExceptionHandler(ObjectProvider<Clock> clockProvider) {
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

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "RATE_LIMIT_EXCEEDED",
                        ex.getMessage(),
                        HttpStatus.TOO_MANY_REQUESTS.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    // ADR-0017: unserializable document-ingestion metadata is a malformed request (400), not the
    // module's own defect -- previously reached only the blanket
    // @ExceptionHandler(Exception.class) below (now removed) and answered a generic 500 (#1694).
    @ExceptionHandler(InvalidDocumentMetadataException.class)
    ResponseEntity<ApiError> handleInvalidDocumentMetadata(
            InvalidDocumentMetadataException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "INVALID_DOCUMENT_METADATA",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(SessionOwnershipViolationException.class)
    ResponseEntity<ApiError> handleSessionOwnershipViolation(
            SessionOwnershipViolationException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "SESSION_ACCESS_DENIED",
                        ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    // ─── Gate 6 (#1193): write-plan confirmation gate ─────────────────────────

    @ExceptionHandler(WritePlanNotFoundException.class)
    ResponseEntity<ApiError> handleWritePlanNotFound(WritePlanNotFoundException ex, HttpServletRequest request) {
        return writePlanError("WRITE_PLAN_NOT_FOUND", ex.getMessage(), HttpStatus.NOT_FOUND, request);
    }

    @ExceptionHandler(WritePlanConflictException.class)
    ResponseEntity<ApiError> handleWritePlanConflict(WritePlanConflictException ex, HttpServletRequest request) {
        return writePlanError("WRITE_PLAN_CONFLICT", ex.getMessage(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(WritePlanExpiredException.class)
    ResponseEntity<ApiError> handleWritePlanExpired(WritePlanExpiredException ex, HttpServletRequest request) {
        return writePlanError("WRITE_PLAN_EXPIRED", ex.getMessage(), HttpStatus.GONE, request);
    }

    @ExceptionHandler(WritePlanStaleException.class)
    ResponseEntity<ApiError> handleWritePlanStale(WritePlanStaleException ex, HttpServletRequest request) {
        return writePlanError("WRITE_PLAN_STALE", ex.getMessage(), HttpStatus.CONFLICT, request);
    }

    @ExceptionHandler(WritePlanExecutionException.class)
    ResponseEntity<ApiError> handleWritePlanExecution(WritePlanExecutionException ex, HttpServletRequest request) {
        logger.error("Write-plan execution failed", ex);
        return writePlanError("WRITE_PLAN_EXECUTION_FAILED", ex.getMessage(), HttpStatus.BAD_GATEWAY, request);
    }

    private ResponseEntity<ApiError> writePlanError(
            String code, String message, HttpStatus status, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(status)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId.toString()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    ResponseEntity<ApiError> handleUnsupported(UnsupportedOperationException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "NOT_IMPLEMENTED",
                        ex.getMessage(),
                        HttpStatus.NOT_IMPLEMENTED.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "UNAUTHORIZED",
                        "Authentication is required",
                        HttpStatus.UNAUTHORIZED.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(ApiError.of(
                        "FORBIDDEN",
                        "Insufficient permissions",
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId.toString()));
    }
}

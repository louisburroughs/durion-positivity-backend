package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Module-local exception advice for pos-bulk-loader's request-facing endpoints.
 *
 * <p>This class deliberately does NOT map bare {@code IllegalArgumentException} (issue #1694):
 * that type is not exclusive to this module's own validation — Hibernate/JPA throw it for an
 * invalid query, {@code UUID.fromString} throws it on malformed stored data, and a JPA converter
 * can raise it while hydrating an entity. Catching it here previously turned any such
 * server-side defect into a client-facing 400 that also echoed the exception's raw message
 * (internal class names, JPQL, file paths) straight into the response body.
 *
 * <p>Every {@code new IllegalArgumentException(...)} this module throws was audited for issue
 * #1694 (see call sites in {@code config.BulkLoadJobFactory}, {@code
 * service.BulkIngestResultRecorder}, {@code service.SpringBatchBulkLoadLauncher} and {@code
 * service.BulkLoadJobServiceImpl}): every one is a defensive/internal-invariant guard that either
 * runs inside asynchronous Spring Batch step execution (never reaches this advice at all — Spring
 * Batch records it against the {@code JobExecution} instead) or guards a precondition its only
 * caller already enforces before invoking it. None validates client-supplied request shape, so
 * none needed a module-owned replacement type. An unexpected {@code IllegalArgumentException} now
 * falls through to {@code pos-web-common}'s platform-wide {@code GlobalApiExceptionHandler}
 * fallback (this module depends on it transitively via {@code pos-security-common}), which
 * answers a generic, correlated 500 INTERNAL_ERROR instead of echoing the exception text.
 *
 * <p>No {@code @ExceptionHandler(Exception.class)} is declared here either, for the same reason:
 * a module-local catch-all would swallow every unmapped exception before the shared advice ever
 * saw it (Spring picks the first {@code @ControllerAdvice} bean with a matching handler method),
 * defeating the platform fallback's correlated-500 guarantee.
 *
 * <p>Per ADR-0017 §4 every response below carries the correlation id in both the body and the
 * {@code X-Correlation-Id} response header. That header is set once, on the returned {@code
 * ResponseEntity}. Writing it onto the {@code HttpServletResponse} as well would be redundant
 * rather than additive — when a {@code ResponseEntity} declares a header, Spring replaces whatever
 * the servlet response already held for that name, so the entity's value is the one that reaches
 * the client either way. {@code Tus-Resumable} is different and is set on the servlet response,
 * because the entity does not declare it.
 *
 * <p>#1716: these handlers answered Spring's bare {@code ProblemDetail} until now. ADR-0017 §3
 * makes the {@link ApiError} envelope ({@code code}, {@code message}, {@code status}, {@code
 * timestamp}, {@code correlationId}) the contract for every non-2xx body, and ADR-0056's
 * "Alternatives Considered" calls out advices disagreeing on envelope shape — ProblemDetail vs
 * ApiError — as precisely the drift it was written to stop. #1694 had converted only the handlers
 * it touched, leaving this advice internally inconsistent; it is finished here. Every handler now
 * carries a machine-readable {@code code}, which a ProblemDetail never had.
 */
@RestControllerAdvice
@Slf4j
public class BulkLoaderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final Clock clock;

    public BulkLoaderExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(JobOwnershipViolationException.class)
    public ResponseEntity<ApiError> handleOwnershipViolation(
            JobOwnershipViolationException ex, HttpServletRequest request, HttpServletResponse response) {
        return envelope(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied", request, response);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NoSuchElementException ex, HttpServletRequest request, HttpServletResponse response) {
        return envelope(HttpStatus.NOT_FOUND, "BULK_JOB_NOT_FOUND", ex.getMessage(), request, response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(
            IllegalStateException ex, HttpServletRequest request, HttpServletResponse response) {
        return envelope(HttpStatus.CONFLICT, "BULK_JOB_INVALID_STATE", ex.getMessage(), request, response);
    }

    @ExceptionHandler(TusOffsetConflictException.class)
    public ResponseEntity<ApiError> handleTusConflict(
            TusOffsetConflictException ex, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Tus-Resumable", "1.0.0");
        return envelope(HttpStatus.CONFLICT, "TUS_OFFSET_CONFLICT", ex.getMessage(), request, response);
    }

    @ExceptionHandler(TusUploadExpiredException.class)
    public ResponseEntity<ApiError> handleTusExpired(
            TusUploadExpiredException ex, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Tus-Resumable", "1.0.0");
        return envelope(HttpStatus.GONE, "TUS_UPLOAD_EXPIRED", ex.getMessage(), request, response);
    }

    /**
     * Field-level failures move into {@code fieldErrors} rather than being flattened into one
     * semicolon-joined string, which is what the envelope's {@code fieldErrors} array exists for
     * and what {@code docs/ERROR_ENVELOPE.md} tells clients to render.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        "VALIDATION_ERROR",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    private ResponseEntity<ApiError> envelope(
            HttpStatus status, String code, String message, HttpServletRequest request, HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code,
                        message != null ? message : status.getReasonPhrase(),
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        if (header == null || header.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return header.trim();
    }
}

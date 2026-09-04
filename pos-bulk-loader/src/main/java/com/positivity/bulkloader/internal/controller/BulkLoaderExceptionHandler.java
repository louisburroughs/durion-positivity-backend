package com.positivity.bulkloader.internal.controller;

import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.exception.TusOffsetConflictException;
import com.positivity.bulkloader.internal.exception.TusUploadExpiredException;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.NoSuchElementException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
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
 * <p>Per ADR-0017 §4 every response below carries the correlation id in both the body ({@code
 * correlationId} extension property on the {@link ProblemDetail}) and the {@code
 * X-Correlation-Id} response header — this class previously set neither.
 */
@RestControllerAdvice
@Slf4j
public class BulkLoaderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(JobOwnershipViolationException.class)
    public ProblemDetail handleOwnershipViolation(
            JobOwnershipViolationException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.FORBIDDEN, "Access denied", request, response);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(
            NoSuchElementException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request, response);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(
            IllegalStateException ex, HttpServletRequest request, HttpServletResponse response) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), request, response);
    }

    @ExceptionHandler(TusOffsetConflictException.class)
    public ProblemDetail handleTusConflict(
            TusOffsetConflictException ex, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Tus-Resumable", "1.0.0");
        return problem(HttpStatus.CONFLICT, ex.getMessage(), request, response);
    }

    @ExceptionHandler(TusUploadExpiredException.class)
    public ProblemDetail handleTusExpired(
            TusUploadExpiredException ex, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Tus-Resumable", "1.0.0");
        return problem(HttpStatus.GONE, ex.getMessage(), request, response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + "; " + b);
        if (detail.isBlank()) {
            detail = "Validation failed";
        }
        return problem(HttpStatus.BAD_REQUEST, detail, request, response);
    }

    private ProblemDetail problem(
            HttpStatus status, String detail, HttpServletRequest request, HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty("correlationId", correlationId);
        return problemDetail;
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        if (header == null || header.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return header.trim();
    }
}

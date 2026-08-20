package com.positivity.workorder.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.exception.InsufficientPartAvailabilityException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.internal.exception.TimeEntryNotFoundException;
import com.positivity.workorder.internal.exception.TimeEntryStateException;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionLockedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkSessionStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final Clock clock;
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    public GlobalExceptionHandler(ObjectProvider<Clock> clockProvider) {
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    @ExceptionHandler(WorkorderNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkorderNotFound(WorkorderNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    /**
     * A shortfall is a conflict with current stock, not a malformed request, and it is actionable:
     * the advisor can order, transfer, or substitute. The nextAction says so rather than leaving a
     * dead end (ADR-0017 response-code matrix, docs/ERROR_ENVELOPE.md).
     */
    @ExceptionHandler(InsufficientPartAvailabilityException.class)
    public ResponseEntity<ApiError> handleInsufficientPartAvailability(
            InsufficientPartAvailabilityException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                "INSUFFICIENT_PART_AVAILABILITY",
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getPartLineId() == null ? null : ex.getPartLineId().toString(),
                InsufficientPartAvailabilityException.NEXT_ACTION,
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.CONFLICT);
    }

    /**
     * A quantity the referenced product's catalog declaration does not permit (ADR-0055, #1413).
     *
     * <p>422 rather than 400: the payload is well-formed and the field is within its declared
     * bounds. What it violates is a rule about the product it names, which nothing but a product
     * lookup could have known — the same reason the check cannot live in bean validation.
     *
     * <p>Carries both a {@code fieldErrors} entry, so a form can mark the quantity box, and a
     * {@code nextAction}, so the counter is told what to enter instead of hitting a dead end
     * (docs/ERROR_ENVELOPE.md).
     */
    @ExceptionHandler(FractionalQuantityNotAllowedException.class)
    public ResponseEntity<ApiError> handleFractionalQuantityNotAllowed(
            FractionalQuantityNotAllowedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = new ApiError(
                "FRACTIONAL_QUANTITY_NOT_ALLOWED",
                ex.getMessage(),
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                Instant.now(clock).toString(),
                correlationId,
                List.of(new ApiError.FieldError(FractionalQuantityNotAllowedException.FIELD, ex.getMessage())),
                null,
                ex.getNextAction(),
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(WorkSessionNotFoundException.class)
    public ResponseEntity<ApiError> handleWorkSessionNotFound(
            WorkSessionNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "WORK_SESSION_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(BreakSegmentNotFoundException.class)
    public ResponseEntity<ApiError> handleBreakSegmentNotFound(
            BreakSegmentNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "BREAK_SEGMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TravelSegmentNotFoundException.class)
    public ResponseEntity<ApiError> handleTravelSegmentNotFound(
            TravelSegmentNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "TRAVEL_SEGMENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TravelSegmentConflictException.class)
    public ResponseEntity<ApiError> handleTravelSegmentConflict(
            TravelSegmentConflictException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "TRAVEL_SEGMENT_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(TimeEntryNotFoundException.class)
    public ResponseEntity<ApiError> handleTimeEntryNotFound(TimeEntryNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "TIME_ENTRY_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(TimeEntryStateException.class)
    public ResponseEntity<ApiError> handleTimeEntryState(TimeEntryStateException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "TIME_ENTRY_INVALID_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(DuplicateSubstituteLinkException.class)
    public ResponseEntity<ApiError> handleDuplicateSubstituteLink(
            DuplicateSubstituteLinkException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "DUPLICATE_SUBSTITUTE_LINK", ex.getMessage(), request);
    }

    @ExceptionHandler(SubstituteLinkNotFoundException.class)
    public ResponseEntity<ApiError> handleSubstituteLinkNotFound(
            SubstituteLinkNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, "SUBSTITUTE_LINK_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(StaleSubstituteLinkVersionException.class)
    public ResponseEntity<ApiError> handleStaleSubstituteLinkVersion(
            StaleSubstituteLinkVersionException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "STALE_SUBSTITUTE_LINK_VERSION", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request);
    }

    @ExceptionHandler({
        WorkSessionOverlapException.class,
        WorkSessionStateException.class,
        WorkSessionLockedException.class
    })
    public ResponseEntity<ApiError> handleWorkSessionConflict(RuntimeException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.withFieldErrors(
                "VALIDATION_FAILED",
                "Request validation failed",
                HttpStatus.BAD_REQUEST.value(),
                Instant.now(clock).toString(),
                correlationId,
                fieldErrors);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.BAD_REQUEST);
    }

    private ResponseEntity<ApiError> buildErrorResponse(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body =
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

package com.positivity.workorder.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.CustomerApprovalInvalidException;
import com.positivity.workorder.internal.exception.CustomerRequirementsNotMetException;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.exception.InsufficientPartAvailabilityException;
import com.positivity.workorder.internal.exception.PromotionIdempotencyInconsistencyException;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.exception.UomConversionUndefinedException;
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

    /**
     * How long a caller should wait before retrying a promotion blocked by a
     * customer-requirements verdict that has not replicated yet (#1477). Short: the
     * projection lag this covers is sub-second in practice.
     */
    private static final int REQUIREMENTS_RETRY_AFTER_SECONDS = 2;

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

    /**
     * A part line's {@code uomCode} names no conversion row for the referenced product (ADR-0055
     * stage 3, #1415). 422, matching pos-inventory's own {@code UOM_CONVERSION_UNDEFINED} — never
     * a silent 1:1 assumption.
     */
    @ExceptionHandler(UomConversionUndefinedException.class)
    public ResponseEntity<ApiError> handleUomConversionUndefined(
            UomConversionUndefinedException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, UomConversionUndefinedException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * No estimate for the requested id (#1477). Promotion used to answer this with a bodiless
     * {@code 400} shared with two other conditions; it is a {@code 404} with a code of its own.
     */
    @ExceptionHandler(EstimateNotFoundException.class)
    public ResponseEntity<ApiError> handleEstimateNotFound(EstimateNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, EstimateNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * The customer's requirements verdict blocked workorder creation (#1477).
     *
     * <p>Split by whether retrying can help, because that is the distinction the empty {@code 400}
     * destroyed. A verdict that has not replicated yet is {@code 503} with {@code Retry-After} —
     * the request is fine and the same call succeeds shortly. A verdict that is known and negative
     * is {@code 409}: a conflict with the customer's state that no retry resolves.
     */
    @ExceptionHandler(CustomerRequirementsNotMetException.class)
    public ResponseEntity<ApiError> handleCustomerRequirementsNotMet(
            CustomerRequirementsNotMetException ex, HttpServletRequest request) {
        HttpStatus status = ex.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.CONFLICT;
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                ex.getErrorCode(),
                ex.getMessage(),
                status.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getCustomerId() == null ? null : ex.getCustomerId().toString(),
                ex.getNextAction(),
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        if (ex.isRetryable()) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(REQUIREMENTS_RETRY_AFTER_SECONDS));
        }
        return new ResponseEntity<>(body, headers, status);
    }

    /**
     * A workorder claims an approval its own state does not back (#1477): a conflict with that
     * state, never a malformed request.
     */
    @ExceptionHandler(CustomerApprovalInvalidException.class)
    public ResponseEntity<ApiError> handleCustomerApprovalInvalid(
            CustomerApprovalInvalidException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                CustomerApprovalInvalidException.ERROR_CODE,
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getWorkorderId() == null ? null : ex.getWorkorderId().toString(),
                CustomerApprovalInvalidException.NEXT_ACTION,
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.CONFLICT);
    }

    /**
     * A promotion precondition failed (#1477). The structured {@code PromotionErrorCode} becomes
     * the envelope's {@code code}, so a caller sees which precondition failed instead of a bare
     * {@code 409}. {@code ESTIMATE_NOT_FOUND} keeps a {@code 404}; an {@code ALREADY_PROMOTED}
     * that carries its existing workorder is answered with that workorder by the promote endpoint
     * itself and only reaches here when the workorder cannot be loaded.
     */
    @ExceptionHandler(PromotionValidationException.class)
    public ResponseEntity<ApiError> handlePromotionValidation(
            PromotionValidationException ex, HttpServletRequest request) {
        HttpStatus status = ex.getErrorCode() == PromotionValidationException.PromotionErrorCode.ESTIMATE_NOT_FOUND
                ? HttpStatus.NOT_FOUND
                : HttpStatus.CONFLICT;
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                ex.getErrorCode().name(),
                ex.getMessage(),
                status.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getExistingWorkorderId() == null
                        ? null
                        : ex.getExistingWorkorderId().toString(),
                null,
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, status);
    }

    /**
     * A recorded idempotency key that resolves to no workorder (#1477). A server defect, so it
     * stays a {@code 500} — but enveloped and correlated, rather than the bodiless status the
     * promote endpoint used to build for it.
     */
    @ExceptionHandler(PromotionIdempotencyInconsistencyException.class)
    public ResponseEntity<ApiError> handlePromotionIdempotencyInconsistency(
            PromotionIdempotencyInconsistencyException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                PromotionIdempotencyInconsistencyException.ERROR_CODE,
                ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getWorkorderId().toString(),
                null,
                PromotionIdempotencyInconsistencyException.SUPPORT_ACTION);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.INTERNAL_SERVER_ERROR);
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

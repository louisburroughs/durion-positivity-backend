package com.positivity.workorder.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.workorder.internal.exception.ApprovalConfigurationNotFoundException;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.ChangeRequestNotFoundException;
import com.positivity.workorder.internal.exception.CustomerApprovalInvalidException;
import com.positivity.workorder.internal.exception.CustomerRequirementsNotMetException;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.EstimateIncompleteException;
import com.positivity.workorder.internal.exception.EstimateItemNotFoundException;
import com.positivity.workorder.internal.exception.EstimateNotFoundException;
import com.positivity.workorder.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.workorder.internal.exception.InsufficientPartAvailabilityException;
import com.positivity.workorder.internal.exception.LaborEntryNotFoundException;
import com.positivity.workorder.internal.exception.PartLineNotFoundException;
import com.positivity.workorder.internal.exception.PromotionIdempotencyInconsistencyException;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.PurchaseOrderRequiredException;
import com.positivity.workorder.internal.exception.ServiceLineNotFoundException;
import com.positivity.workorder.internal.exception.ServicePositionInactiveException;
import com.positivity.workorder.internal.exception.ServicePositionInvalidException;
import com.positivity.workorder.internal.exception.ServicePositionOccupiedException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.internal.exception.TechnicianAlreadyAssignedException;
import com.positivity.workorder.internal.exception.TechnicianNotAssignedException;
import com.positivity.workorder.internal.exception.TechnicianNotFoundException;
import com.positivity.workorder.internal.exception.TravelSegmentConflictException;
import com.positivity.workorder.internal.exception.TravelSegmentNotFoundException;
import com.positivity.workorder.internal.exception.UomConversionUndefinedException;
import com.positivity.workorder.internal.exception.WorkSessionLockedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkSessionStateException;
import com.positivity.workorder.internal.exception.WorkorderClosedException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.exception.WorkorderRequestValidationException;
import com.positivity.workorder.internal.exception.WorkorderResourceConflictException;
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

    /**
     * A workorder-domain request fails field-level or request-shape validation (issue #1694).
     * Replaces the module's former blanket {@code @ExceptionHandler(IllegalArgumentException.class)},
     * which also caught server-side defects (Hibernate/JPA lookups, malformed stored data) that
     * happen to throw the same JDK exception and reported them to the client as a bad request.
     * {@code INVALID_ARGUMENT} is the code that blanket handler used, kept here so the wire
     * contract for genuine client validation failures does not drift.
     */
    @ExceptionHandler(WorkorderRequestValidationException.class)
    public ResponseEntity<ApiError> handleWorkorderRequestValidation(
            WorkorderRequestValidationException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST, WorkorderRequestValidationException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * A well-formed request conflicts with the target resource's current state (ADR-0017 §2,
     * issue #1694): a caller-supplied id does not match the resource it targets, or an operation
     * would exceed a quantity the resource's current running totals actually have available. Uses
     * the same {@code CONFLICT} code as {@link #handleIllegalState} so every stateful-collision
     * response in this module carries one consistent code.
     */
    /**
     * An exclusive service position already holds an open workorder (#1984).
     *
     * <p>409 and not 422: the request is well-formed and the position is one the caller may use —
     * it is the position's current occupancy that refuses, and it will stop refusing when the
     * occupying workorder completes, is cancelled, or moves. The occupying workorder rides as
     * {@code referenceId} so a dispatch board can link straight to the job in the bay instead of
     * parsing the message; it is absent when a racing assign lost to the unique index, where the
     * winner is not knowable from inside the losing transaction.
     */
    @ExceptionHandler(ServicePositionOccupiedException.class)
    public ResponseEntity<ApiError> handleServicePositionOccupied(
            ServicePositionOccupiedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                ServicePositionOccupiedException.ERROR_CODE,
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getOccupyingWorkorderId() == null
                        ? null
                        : ex.getOccupyingWorkorderId().toString(),
                null,
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.CONFLICT);
    }

    /**
     * A position that is unknown, or that belongs to another site than the workorder (#1983).
     *
     * <p>422, not 400 and not 404: the payload parses and every field is within its declared type,
     * and the position is not what the URL addresses. What fails is a cross-entity rule, which
     * ADR-0017 §2 places at 422.
     */
    @ExceptionHandler(ServicePositionInvalidException.class)
    public ResponseEntity<ApiError> handleServicePositionInvalid(
            ServicePositionInvalidException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, ServicePositionInvalidException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * A bay or mobile unit that is not active was named as a service position (#2001).
     *
     * <p>Its own code rather than {@code SERVICE_POSITION_INVALID}: the position exists and is at the
     * right site, so the caller's request was not malformed — the resource is out of service, which
     * is a different thing for a dispatcher to act on. 422 for the same reason the invalid-position
     * refusal is (ADR-0017 §2): a cross-entity rule, not a parse failure and not a missing URL target.
     */
    @ExceptionHandler(ServicePositionInactiveException.class)
    public ResponseEntity<ApiError> handleServicePositionInactive(
            ServicePositionInactiveException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, ServicePositionInactiveException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * Position or technician changes asked of a COMPLETED or CANCELLED workorder (#1983).
     *
     * <p>The stable code the story asks for, so a client can distinguish "this job is over" from
     * every other 409 the assignment endpoints can raise.
     */
    @ExceptionHandler(WorkorderClosedException.class)
    public ResponseEntity<ApiError> handleWorkorderClosed(WorkorderClosedException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.CONFLICT, WorkorderClosedException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * Assign was called on a workorder that already has a current technician (#1985).
     *
     * <p>Assign used to overwrite silently, so an accidental double assign looked exactly like a
     * deliberate hand-over. The current technician rides as {@code referenceId} and the
     * {@code nextAction} names the operation that does change technicians.
     */
    @ExceptionHandler(TechnicianAlreadyAssignedException.class)
    public ResponseEntity<ApiError> handleTechnicianAlreadyAssigned(
            TechnicianAlreadyAssignedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                TechnicianAlreadyAssignedException.ERROR_CODE,
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                Instant.now(clock).toString(),
                correlationId,
                ex.getCurrentTechnicianId() == null
                        ? null
                        : ex.getCurrentTechnicianId().toString(),
                TechnicianAlreadyAssignedException.NEXT_ACTION,
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.CONFLICT);
    }

    /**
     * A technician id that names nobody in the {@code ext_person} replica (#1983).
     *
     * <p>422 for the same reason {@link ServicePositionInvalidException} is: a cross-entity rule
     * fails, and the technician is not what the URL addresses.
     */
    @ExceptionHandler(TechnicianNotFoundException.class)
    public ResponseEntity<ApiError> handleTechnicianNotFound(
            TechnicianNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, TechnicianNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    /** Reassign was called on a workorder that has no current technician to reassign from (#1985). */
    @ExceptionHandler(TechnicianNotAssignedException.class)
    public ResponseEntity<ApiError> handleTechnicianNotAssigned(
            TechnicianNotAssignedException ex, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        ApiError body = ApiError.guided(
                TechnicianNotAssignedException.ERROR_CODE,
                ex.getMessage(),
                HttpStatus.CONFLICT.value(),
                Instant.now(clock).toString(),
                correlationId,
                null,
                TechnicianNotAssignedException.NEXT_ACTION,
                null);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(body, headers, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(WorkorderResourceConflictException.class)
    public ResponseEntity<ApiError> handleWorkorderResourceConflict(
            WorkorderResourceConflictException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.CONFLICT, WorkorderResourceConflictException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * A semantically valid estimate approval is missing a purchase order a commercial customer's
     * billing rules require (CAP:092 Story #98, issue #1694). 422, not 400: the payload is
     * well-formed, and whether a PO is required is a documented domain policy resolved by a
     * billing-rules lookup, not request-shape validation.
     */
    @ExceptionHandler(PurchaseOrderRequiredException.class)
    public ResponseEntity<ApiError> handlePurchaseOrderRequired(
            PurchaseOrderRequiredException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, PurchaseOrderRequiredException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * A DRAFT estimate was submitted for approval before it was complete: no customer, no
     * vehicle, no line items, or totals not yet calculated (issue #1791). 422, not 400 and not
     * 409: the submit request carries no body to correct, and the estimate is in the one status
     * that permits submission — what refuses it is an attribute of the target other than its
     * lifecycle status, which ADR-0017 §2 places at 422.
     */
    @ExceptionHandler(EstimateIncompleteException.class)
    public ResponseEntity<ApiError> handleEstimateIncomplete(
            EstimateIncompleteException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY, EstimateIncompleteException.ERROR_CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(ChangeRequestNotFoundException.class)
    public ResponseEntity<ApiError> handleChangeRequestNotFound(
            ChangeRequestNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, ChangeRequestNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(ServiceLineNotFoundException.class)
    public ResponseEntity<ApiError> handleServiceLineNotFound(
            ServiceLineNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, ServiceLineNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(PartLineNotFoundException.class)
    public ResponseEntity<ApiError> handlePartLineNotFound(PartLineNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(HttpStatus.NOT_FOUND, PartLineNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(EstimateItemNotFoundException.class)
    public ResponseEntity<ApiError> handleEstimateItemNotFound(
            EstimateItemNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, EstimateItemNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(ApprovalConfigurationNotFoundException.class)
    public ResponseEntity<ApiError> handleApprovalConfigurationNotFound(
            ApprovalConfigurationNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, ApprovalConfigurationNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(LaborEntryNotFoundException.class)
    public ResponseEntity<ApiError> handleLaborEntryNotFound(
            LaborEntryNotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(
                HttpStatus.NOT_FOUND, LaborEntryNotFoundException.ERROR_CODE, ex.getMessage(), request);
    }

    /**
     * {@code @RequestParam}-level {@code @Min}/{@code @Max} (e.g. the analytics endpoints' {@code
     * limit}/{@code withinDays}, #1593-#1595) validated via class-level {@code @Validated} raises
     * the raw JSR-380 exception through an AOP method interceptor, not Spring's web-native {@code
     * HandlerMethodValidationException} — so unlike {@code MethodArgumentNotValidException} it does
     * not implement {@link org.springframework.web.ErrorResponse} and pos-web-common's platform
     * fallback collapses it to a 500. Without this handler, an out-of-range query parameter on any
     * {@code @Validated} controller in this module is a server error instead of the caller's 400.
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(
                        lastPathSegment(v.getPropertyPath().toString()), v.getMessage()))
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

    private static String lastPathSegment(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
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

package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.exception.EventNotFoundException;
import com.positivity.accounting.internal.exception.ExportJobNotFoundException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidBillAllocationException;
import com.positivity.accounting.internal.exception.PaymentGatewayException;
import com.positivity.accounting.internal.exception.UnsupportedSortPropertyException;
import com.positivity.accounting.internal.exception.VendorBillMatchNotFoundException;
import com.positivity.accounting.internal.exception.VendorBillOperatorActionException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for AP Payment and vendor-bill operator-action endpoints, scoped to this
 * module's controllers.
 *
 * Maps domain exceptions to appropriate HTTP status codes using standard
 * ApiError format:
 * - EventNotFoundException → 404 Not Found
 * - IdempotencyConflictException → 409 Conflict
 * - ExportJobNotFoundException → 404 Not Found
 * - UnsupportedSortPropertyException → 400 Bad Request
 * - PaymentGatewayException → 500 Internal Server Error
 * - InvalidBillAllocationException → 400 Bad Request (AP payment bill allocation)
 * - VendorBillOperatorActionException → 400 Bad Request (vendor-bill match/exception workflow)
 * - VendorBillMatchNotFoundException → 400 Bad Request (no matching vendor bill)
 * - ConstraintViolationException → 400 Bad Request
 *
 * <p>No blanket {@code IllegalArgumentException} handler (issue #1694): every mapped type here
 * was audited and is thrown only for a genuine client-facing condition, so a
 * Hibernate/JPA-thrown {@code IllegalArgumentException} unrelated to this module's validation
 * no longer surfaces as a misleading 400 — it falls through to the pos-web-common catch-all.
 * This advice's {@code basePackages} scope overlaps
 * {@code com.positivity.accounting.internal.config.AccountingExceptionHandler} (unscoped) for
 * every controller in this module; the two are kept separate along their existing thematic
 * split (AP-payment/vendor-bill operator actions here, everything else there) and no exception
 * type is mapped in both — {@code IllegalStateException} deliberately is NOT mapped here
 * (it previously was, duplicating {@code AccountingExceptionHandler#handleIllegalState}, which
 * left the answered error {@code code} dependent on Spring bean registration order — issue
 * #1694 review finding). It is mapped exactly once, in {@code AccountingExceptionHandler}, whose
 * {@code resolveStateErrorCode} distinguishes {@code ENTRY_ALREADY_POSTED} from a generic
 * conflict; that handler answers state conflicts for every controller in this module, this one
 * included.
 */
@RestControllerAdvice(basePackages = "com.positivity.accounting.internal.controller")
@RequiredArgsConstructor
public class APPaymentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(APPaymentExceptionHandler.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(EventNotFoundException.class)
    public ResponseEntity<ApiError> handleEventNotFound(EventNotFoundException ex, HttpServletRequest request) {
        log.warn("Event not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "EVENT_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException ex, HttpServletRequest request) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", ex.getMessage(), request);
    }

    @ExceptionHandler(ExportJobNotFoundException.class)
    public ResponseEntity<ApiError> handleExportJobNotFound(ExportJobNotFoundException ex, HttpServletRequest request) {
        log.warn("Export job not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, "EXPORT_JOB_NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(UnsupportedSortPropertyException.class)
    public ResponseEntity<ApiError> handleUnsupportedSortProperty(
            UnsupportedSortPropertyException ex, HttpServletRequest request) {
        log.warn("Unsupported sort property: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "UNSUPPORTED_SORT_PROPERTY", ex.getMessage(), request);
    }

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiError> handlePaymentGatewayException(
            PaymentGatewayException ex, HttpServletRequest request) {
        if (ex.getPaymentRef() != null) {
            log.error("Payment gateway error for payment {}: {}", ex.getPaymentRef(), ex.getMessage(), ex);
        } else {
            log.error("Payment gateway error: {}", ex.getMessage(), ex);
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "PAYMENT_GATEWAY_FAILURE", ex.getMessage(), request);
    }

    /**
     * AP payment bill-allocation validation failures (executePayment): an allocated bill is
     * missing, unapproved, belongs to a different vendor, or the allocation total exceeds the
     * gross amount. 400 VALIDATION_ERROR, matching the documented contract on
     * {@code POST /v1/accounting/ap/payments}.
     */
    @ExceptionHandler(InvalidBillAllocationException.class)
    public ResponseEntity<ApiError> handleInvalidBillAllocation(
            InvalidBillAllocationException ex, HttpServletRequest request) {
        log.warn("Invalid bill allocation: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    /**
     * Vendor-bill match/exception operator actions (resolveMatchException,
     * selectMatchCandidate): bill/candidate missing, wrong state, already resolved, or an
     * unrecognized resolution action. 400 VALIDATION_ERROR, deliberately not 404 — both
     * endpoints document this explicitly.
     */
    @ExceptionHandler(VendorBillOperatorActionException.class)
    public ResponseEntity<ApiError> handleVendorBillOperatorAction(
            VendorBillOperatorActionException ex, HttpServletRequest request) {
        log.warn("Vendor bill operator action rejected: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    /**
     * An inbound vendor invoice-received event matched no pending receipt/bill for the vendor.
     * 400 VALIDATION_ERROR, matching the documented contract on
     * {@code POST /v1/accounting/ap/bills/match-invoice} ("400 when no pending receipt
     * matches the invoice") — even though ADR-0017 §2 would otherwise favor 422 for this
     * shape of failure, the existing, explicit endpoint contract is preserved so the wire
     * contract does not drift (issue #1694).
     */
    @ExceptionHandler(VendorBillMatchNotFoundException.class)
    public ResponseEntity<ApiError> handleVendorBillMatchNotFound(
            VendorBillMatchNotFoundException ex, HttpServletRequest request) {
        log.warn("No matching vendor bill: {}", ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "NO_MATCHING_VENDOR_BILL", ex.getMessage(), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .findFirst()
                .map(ConstraintViolation::getMessage)
                .orElse("Validation failed");

        log.warn("Validation error: {}", message);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

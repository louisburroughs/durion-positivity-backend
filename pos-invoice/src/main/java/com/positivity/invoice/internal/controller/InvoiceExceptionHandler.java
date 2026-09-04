package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.ExcessiveAdjustmentException;
import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvalidManagerApprovalException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.InvoiceRequestValidationException;
import com.positivity.invoice.internal.exception.ManagerApprovalRequiredException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = InvoiceController.class)
@RequiredArgsConstructor
public class InvoiceExceptionHandler {
    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    /** Recovery hint for the two step-up (403) manager-approval refusals (ADR-0017 §2, #1725). */
    private static final String MANAGER_APPROVAL_NEXT_ACTION =
            "Obtain a manager-approval elevation token for this invoice via elevateManagerApproval and resend it as"
                    + " managerApprovalCode";

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<ApiError> handleInvoiceNotFound(InvoiceNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(InvalidInvoiceStateException.class)
    public ResponseEntity<ApiError> handleInvalidInvoiceState(
            InvalidInvoiceStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "INVALID_STATE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * C3 (ADR-0017): {@link IllegalStateException} maps to HTTP 409 Conflict.
     * Prevents "already finalized" and "POSTED" state errors from returning 500.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "CONFLICT",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * #1694: was a blanket {@code IllegalArgumentException} handler (400 VALIDATION_ERROR),
     * with a special case that attached a {@code managerApprovalCode} field error whenever the
     * message mentioned "approval code" — that blanket also caught whatever
     * {@code IllegalArgumentException} Hibernate/JPA or {@code UUID.fromString} might throw and
     * reported it as a client 400. The approval-code cases now have their own step-up
     * authorization types (403; see {@link #handleManagerApprovalRequired} and {@link
     * #handleInvalidManagerApproval}), so the field-error special-casing no longer applies to
     * anything and is dropped; this handler now only maps this module's own request-shape
     * validation type. Status/code unchanged for the remaining (genuine) 400 cases.
     */
    @ExceptionHandler(InvoiceRequestValidationException.class)
    public ResponseEntity<ApiError> handleValidation(InvoiceRequestValidationException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "VALIDATION_ERROR",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * ADR-0017 §2 question 1 (#1725): the finalize/revert request is shape-valid, but the
     * manager-approval permission matrix requires a step-up elevation token that was not
     * supplied. That is a refusal about what the caller is allowed to do, so it answers 403 with
     * a {@code nextAction} naming the recovery. #1694 had first moved this out of the blanket
     * 400 handler above (with a fieldErrors hint) to a 422. Documented in docs/ERROR_ENVELOPE.md.
     */
    @ExceptionHandler(ManagerApprovalRequiredException.class)
    public ResponseEntity<ApiError> handleManagerApprovalRequired(
            ManagerApprovalRequiredException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.guided(
                        "MANAGER_APPROVAL_REQUIRED",
                        ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        null,
                        MANAGER_APPROVAL_NEXT_ACTION,
                        null));
    }

    /**
     * ADR-0017 §2 question 1 (#1725): a supplied manager-approval elevation token does not verify
     * (wrong scope, tampered, or expired) — a step-up credential the server considers
     * insufficient, so 403, mirroring {@link #handleManagerApprovalRequired}. #1694 had first
     * moved this out of the blanket 400 handler above to a 422. Documented in
     * docs/ERROR_ENVELOPE.md.
     */
    @ExceptionHandler(InvalidManagerApprovalException.class)
    public ResponseEntity<ApiError> handleInvalidManagerApproval(
            InvalidManagerApprovalException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.guided(
                        "MANAGER_APPROVAL_INVALID",
                        ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        null,
                        MANAGER_APPROVAL_NEXT_ACTION,
                        null));
    }

    /**
     * (b) ADR-0017 §2: the adjustment itself is shape-valid, but combined with the invoice's
     * existing state it would drive the total negative — a documented domain-policy violation,
     * not a malformed request. New code, documented in docs/ERROR_ENVELOPE.md.
     */
    @ExceptionHandler(ExcessiveAdjustmentException.class)
    public ResponseEntity<ApiError> handleExcessiveAdjustment(
            ExcessiveAdjustmentException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "EXCESSIVE_ADJUSTMENT",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

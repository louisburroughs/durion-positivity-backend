package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.DepositCreditNotFoundException;
import com.positivity.invoice.internal.exception.InvoiceRequestValidationException;
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

/**
 * ApiError mapping for deposit-credit endpoints (odoo-parity story E4, #1085). Scoped to
 * {@link DepositCreditController} so it does not shadow the invoice advice.
 */
@RestControllerAdvice(assignableTypes = DepositCreditController.class)
@RequiredArgsConstructor
public class DepositCreditExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(DepositCreditNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(DepositCreditNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "DEPOSIT_CREDIT_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * #1694: was a blanket {@code IllegalArgumentException} handler at 422 — {@code
     * IllegalArgumentException} is also what Hibernate/JPA and {@code UUID.fromString} throw for
     * reasons unrelated to this module's own validation, so that blanket risked reporting a
     * server-side defect as a client error. {@link InvoiceRequestValidationException} is thrown
     * only at sites that intentionally validate a caller-supplied value (positive amount,
     * recognized sourceType); everything else now falls through to pos-web-common's
     * platform-wide 500 fallback. ADR-0017 §1: malformed/field-shape validation maps to 400, not
     * 422 — the amount/sourceType checks here are request-shape validation, not a domain-policy
     * violation on a state-dependent operation, so the status moves from the prior 422 to 400
     * (existing code preserved for wire-contract stability).
     */
    @ExceptionHandler(InvoiceRequestValidationException.class)
    public ResponseEntity<ApiError> handleValidation(InvoiceRequestValidationException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "DEPOSIT_CREDIT_INVALID_ARGUMENT",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
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

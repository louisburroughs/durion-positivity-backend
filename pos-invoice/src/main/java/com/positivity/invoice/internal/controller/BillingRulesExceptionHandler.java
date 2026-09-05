package com.positivity.invoice.internal.controller;

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
 * Scoped error mapping for {@link BillingRulesController} (issue #1713).
 *
 * <p>Before this advice existed, the controller was covered by none of the module's
 * {@code @RestControllerAdvice(assignableTypes = ...)} classes, so the rejection of an unknown
 * {@code paymentTermsCode} — a caller-supplied value, and an outcome the endpoint's own
 * {@code @Operation} prose describes — fell through to pos-web-common's platform fallback and
 * answered {@code 500 INTERNAL_ERROR}. ADR-0017 §1 makes a rejected request value a 400.
 *
 * <p>Deliberately narrow: only the module's own {@link InvoiceRequestValidationException} is
 * mapped. Everything else — including a bare {@code IllegalArgumentException} from Hibernate/JPA
 * or {@code UUID.fromString} — still reaches {@code GlobalApiExceptionHandler}'s catch-all as a
 * generic, correlated 500 that echoes nothing (ADR-0056 §1). Restoring a blanket handler here
 * would reintroduce exactly what #1694 removed from this module.
 */
@RestControllerAdvice(assignableTypes = BillingRulesController.class)
@RequiredArgsConstructor
public class BillingRulesExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final Clock clock;

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

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

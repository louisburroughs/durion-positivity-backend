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
 * ApiError mapping for {@link InvoiceSearchController} (#1599, E11's {@code issuedTo}-before-
 * {@code issuedFrom} rejection). Scoped to {@link InvoiceSearchController} so it does not shadow
 * {@link InvoiceExceptionHandler}, mirroring {@link InvoiceAnalyticsExceptionHandler}.
 */
@RestControllerAdvice(assignableTypes = InvoiceSearchController.class)
@RequiredArgsConstructor
public class InvoiceSearchExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    /**
     * Bad issued-date window (issuedTo before issuedFrom) (#1694: was a blanket {@code
     * IllegalArgumentException} handler; narrowed to this module-owned type so a server-side
     * defect no longer reports as a client 400. Status/code unchanged.)
     */
    @ExceptionHandler(InvoiceRequestValidationException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            InvoiceRequestValidationException ex, HttpServletRequest request) {
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

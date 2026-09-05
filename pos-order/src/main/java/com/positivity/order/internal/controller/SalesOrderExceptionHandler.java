package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderUnprocessableException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = SalesOrderController.class)
@RequiredArgsConstructor
@Slf4j
public class SalesOrderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(SalesOrderNotFoundException.class)
    public ResponseEntity<ApiError> handleSalesOrderNotFound(
            SalesOrderNotFoundException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(com.positivity.order.internal.exception.TaxUnavailableException.class)
    public ResponseEntity<ApiError> handleTaxUnavailable(
            com.positivity.order.internal.exception.TaxUnavailableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_TAX_UNAVAILABLE",
                        ex.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(com.positivity.order.internal.exception.OrderVoidBlockedException.class)
    public ResponseEntity<ApiError> handleVoidBlocked(
            com.positivity.order.internal.exception.OrderVoidBlockedException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_VOID_BLOCKED",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(com.positivity.order.internal.exception.InvoicingUnavailableException.class)
    public ResponseEntity<ApiError> handleInvoicingUnavailable(
            com.positivity.order.internal.exception.InvoicingUnavailableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_INVOICING_UNAVAILABLE",
                        ex.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(com.positivity.order.internal.exception.InvalidCustomerException.class)
    public ResponseEntity<ApiError> handleInvalidCustomer(
            com.positivity.order.internal.exception.InvalidCustomerException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_INVALID_CUSTOMER",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(InvalidSkuException.class)
    public ResponseEntity<ApiError> handleInvalidSku(InvalidSkuException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_INVALID_SKU",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A domain rule refusing a structurally valid cart request on its merits — an empty cart, an
     * unresolvable price, a serial/lot count that does not match the line quantity. ADR-0017 §2
     * makes that a 422.
     *
     * <p>#1730: this replaces a blanket {@code @ExceptionHandler(IllegalStateException.class)}
     * that answered 422 for everything that type carried here, including two request-shape errors
     * ADR-0017 §1 makes a 400, one stateful collision §2 makes a 409, and — in the sibling
     * advices, which had settled on 409 — outright downstream failures. The bare type is no
     * longer mapped anywhere in this module, so anything still throwing it reaches
     * pos-web-common's platform advice as a correlated 500. Same reasoning as #1694, applied to
     * {@code IllegalStateException}.
     */
    @ExceptionHandler(SalesOrderUnprocessableException.class)
    public ResponseEntity<ApiError> handleUnprocessableRequest(
            SalesOrderUnprocessableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_UNPROCESSABLE",
                        ex.getMessage(),
                        422,
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A malformed cart/sales-order request (half-specified deposit-source pair, missing
     * locationId with no session fallback, blank checkout Idempotency-Key, an unsupported
     * tenderType, or an unrecognised status/sourceType filter). Maps to 400 per ADR-0017 —
     * request-shape validation, not a domain-policy refusal — replacing the former blanket
     * {@code IllegalArgumentException} handler that answered 422 for this same case (a status the
     * issue #1694 audit found was never deliberate). The {@code ORDER_INVALID_ARGUMENT} code is
     * unchanged so the wire contract's error code does not drift; only the status moved.
     */
    @ExceptionHandler(SalesOrderRequestValidationException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(
            SalesOrderRequestValidationException ex, HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Invalid request: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_INVALID_ARGUMENT",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        String correlationId = Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
        log.warn("Access denied: correlationId={}", correlationId, ex);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "ORDER_FORBIDDEN",
                        ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(),
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

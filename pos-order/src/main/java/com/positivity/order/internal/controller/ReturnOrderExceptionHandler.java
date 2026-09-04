package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.OverCapReturnException;
import com.positivity.order.internal.exception.ReturnLineNotReturnableException;
import com.positivity.order.internal.exception.ReturnOrderNotFoundException;
import com.positivity.order.internal.exception.ReturnRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.WarrantyReturnRoutingException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ApiError mapping for returns endpoints (parity stories F1/F2). Scoped to
 * {@link ReturnOrderController} so it does not shadow the sales-order advice. The over-cap error
 * carries each offending line's {@code returnableQty} as field errors (spec R5.2).
 */
@RestControllerAdvice(assignableTypes = ReturnOrderController.class)
@RequiredArgsConstructor
@Slf4j
public class ReturnOrderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler({ReturnOrderNotFoundException.class, SalesOrderNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "RETURN_NOT_FOUND",
                        ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(OverCapReturnException.class)
    public ResponseEntity<ApiError> handleOverCap(OverCapReturnException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        List<ApiError.FieldError> fieldErrors = ex.getOffendingLines().stream()
                .map(cap -> new ApiError.FieldError(
                        cap.orderLineId().toString(),
                        "requested " + cap.requested() + " but returnableQty is " + cap.returnableQty()))
                .toList();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.withFieldErrors(
                        "RETURN_OVER_CAP",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    @ExceptionHandler(WarrantyReturnRoutingException.class)
    public ResponseEntity<ApiError> handleWarrantyRouting(
            WarrantyReturnRoutingException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "RETURN_WARRANTY_ROUTING",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "RETURN_INVALID_STATE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A malformed return request (no lines, a duplicate/unknown line reference, a non-positive
     * returnQty, or an unrecognised refundMethod/condition). Maps to 400 per ADR-0017 —
     * request-shape validation, not a domain-policy refusal — replacing the former blanket
     * {@code IllegalArgumentException} handler that answered 422 for this same case (a status the
     * issue #1694 audit found was never deliberate). The {@code RETURN_INVALID_ARGUMENT} code is
     * unchanged so the wire contract's error code does not drift; only the status moved.
     */
    @ExceptionHandler(ReturnRequestValidationException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(
            ReturnRequestValidationException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "RETURN_INVALID_ARGUMENT",
                        ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    /**
     * A requested line is not returnable per policy: well-formed request, existing line, refused
     * on its merits. New code (issue #1694), split out of the former blanket 422 catch-all so a
     * caller can tell this apart from a malformed request.
     */
    @ExceptionHandler(ReturnLineNotReturnableException.class)
    public ResponseEntity<ApiError> handleLineNotReturnable(
            ReturnLineNotReturnableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "RETURN_LINE_NOT_RETURNABLE",
                        ex.getMessage(),
                        HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        return Optional.ofNullable(request.getHeader(X_CORRELATION_ID))
                .filter(header -> !header.isBlank())
                .orElse(UUIDv7Generator.generate().toString());
    }
}

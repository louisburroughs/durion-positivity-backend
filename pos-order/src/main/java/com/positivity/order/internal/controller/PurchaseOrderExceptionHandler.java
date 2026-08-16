package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.PurchaseOrderNotFoundException;
import com.positivity.order.internal.exception.PurchaseOrderNotTransmittableException;
import com.positivity.order.internal.exception.UomConversionUndefinedException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns purchase-order failures into the {@code ApiError} envelope its endpoints promise.
 *
 * <h2>Why this exists</h2>
 *
 * pos-order scopes its advices per controller, and the purchase-order controller arrived without
 * one when the aggregate moved here (#1334). Its endpoints have documented 404 and 409 responses
 * since then and would have produced neither: every one of these exceptions fell through to the
 * default handler and came back as a 500 with a body no client could read. Callers were being
 * promised a contract that did not exist.
 *
 * <p>The transmission codes are surfaced individually rather than collapsed into one "cannot
 * transmit" message, because each names a different thing for somebody to go and fix — a missing
 * supplier profile, an article without a code, a send already in flight — and a caller that cannot
 * tell them apart cannot act on any of them.
 */
@RestControllerAdvice(assignableTypes = PurchaseOrderController.class)
@RequiredArgsConstructor
@Slf4j
public class PurchaseOrderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(PurchaseOrderNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(PurchaseOrderNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "PURCHASE_ORDER_NOT_FOUND", ex.getMessage(), request);
    }

    /**
     * The order cannot be sent as it stands.
     *
     * <p>422 rather than 409: the request is understood and the order exists, but its current
     * content or state makes sending it wrong. The exception's own code travels through, so a
     * caller can distinguish "add a supplier" from "wait for the answer to the last send".
     */
    @ExceptionHandler(PurchaseOrderNotTransmittableException.class)
    public ResponseEntity<ApiError> handleNotTransmittable(
            PurchaseOrderNotTransmittableException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage(), request);
    }

    /** A line keyed in a unit with no conversion to base; never a silent 1:1 assumption. */
    @ExceptionHandler(UomConversionUndefinedException.class)
    public ResponseEntity<ApiError> handleUomConversionUndefined(
            UomConversionUndefinedException ex, HttpServletRequest request) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ex.getErrorCode(), ex.getMessage(), request);
    }

    /** Lifecycle refusals: approving a non-draft, cancelling a received order. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleInvalidState(IllegalStateException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "PURCHASE_ORDER_INVALID_STATE", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Invalid purchase-order request", ex);
        return respond(HttpStatus.BAD_REQUEST, "PURCHASE_ORDER_BAD_REQUEST", ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "PURCHASE_ORDER_FORBIDDEN", ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> respond(
            HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}

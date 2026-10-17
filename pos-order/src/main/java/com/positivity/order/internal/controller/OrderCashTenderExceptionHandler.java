package com.positivity.order.internal.controller;

import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.CashMovementTaxRefusedException;
import com.positivity.order.internal.exception.CashTenderRefusedException;
import com.positivity.order.internal.exception.CurrencyNotSupportedException;
import com.positivity.order.internal.exception.InvalidOrderStateTransitionException;
import com.positivity.order.internal.exception.InvoicingUnavailableException;
import com.positivity.order.internal.exception.OrderConflictException;
import com.positivity.order.internal.exception.RegisterSessionConflictException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * ApiError mapping for the register cash tender (ADR-0067 A8, #2662; ADR-0017: one condition, one status, named
 * codes). Mirrors {@link SalesOrderExceptionHandler} for the shared conditions and names the tender's own.
 */
@RestControllerAdvice(assignableTypes = OrderCashTenderController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class OrderCashTenderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String RETRY_AFTER_SECONDS = "5";

    private final Clock clock;

    @ExceptionHandler(SalesOrderNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(SalesOrderNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler({SalesOrderRequestValidationException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ApiError> handleInvalidArgument(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "ORDER_INVALID_ARGUMENT", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(InvalidOrderStateTransitionException.class)
    public ResponseEntity<ApiError> handleState(InvalidOrderStateTransitionException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ORDER_INVALID_STATE_TRANSITION", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(RegisterSessionConflictException.class)
    public ResponseEntity<ApiError> handleSession(RegisterSessionConflictException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "REGISTER_SESSION_CONFLICT", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(CartIdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotency(
            CartIdempotencyConflictException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ORDER_IDEMPOTENCY_CONFLICT", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(OrderConflictException.class)
    public ResponseEntity<ApiError> handleConflict(OrderConflictException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "ORDER_CONFLICT", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(CashTenderRefusedException.class)
    public ResponseEntity<ApiError> handleRefused(CashTenderRefusedException ex, HttpServletRequest request) {
        return respond(
                HttpStatus.UNPROCESSABLE_CONTENT, ex.code().name(), ex.getMessage(), ex.fieldErrors(), request);
    }

    @ExceptionHandler(CashMovementTaxRefusedException.class)
    public ResponseEntity<ApiError> handlePrecision(CashMovementTaxRefusedException ex, HttpServletRequest request) {
        return respond(
                HttpStatus.UNPROCESSABLE_CONTENT, ex.code().name(), ex.getMessage(), ex.fieldErrors(), request);
    }

    @ExceptionHandler(CurrencyNotSupportedException.class)
    public ResponseEntity<ApiError> handleCurrency(CurrencyNotSupportedException ex, HttpServletRequest request) {
        return respond(
                HttpStatus.UNPROCESSABLE_CONTENT, "CURRENCY_NOT_SUPPORTED", ex.getMessage(), List.of(), request);
    }

    @ExceptionHandler(InvoicingUnavailableException.class)
    public ResponseEntity<ApiError> handleInvoicing(InvoicingUnavailableException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(X_CORRELATION_ID, correlationId)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(ApiError.of(
                        "ORDER_INVOICING_UNAVAILABLE",
                        ex.getMessage(),
                        HttpStatus.SERVICE_UNAVAILABLE.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "ORDER_FORBIDDEN", ex.getMessage(), List.of(), request);
    }

    private ResponseEntity<ApiError> respond(
            HttpStatus status,
            String code,
            String message,
            List<ApiError.FieldError> fieldErrors,
            HttpServletRequest request) {
        String correlationId = correlationId(request);
        String timestamp = Instant.now(clock).toString();
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(
                        fieldErrors.isEmpty()
                                ? ApiError.of(code, message, status.value(), timestamp, correlationId)
                                : ApiError.withFieldErrors(
                                        code, message, status.value(), timestamp, correlationId, fieldErrors));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank()) ? header : UUIDv7Generator.generate().toString();
    }
}

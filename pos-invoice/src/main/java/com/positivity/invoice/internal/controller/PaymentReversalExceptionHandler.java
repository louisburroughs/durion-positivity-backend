package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InsufficientRefundableAmountException;
import com.positivity.invoice.internal.exception.InvalidPaymentStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.exception.PaymentGatewayException;
import com.positivity.invoice.internal.exception.PaymentIntentNotFoundException;
import com.positivity.invoice.internal.exception.PaymentWindowExpiredException;
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

@RestControllerAdvice(assignableTypes = {PaymentReversalController.class, StandaloneRefundController.class})
@RequiredArgsConstructor
public class PaymentReversalExceptionHandler {

    private final Clock clock;
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler(PaymentIntentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(PaymentIntentNotFoundException ex, HttpServletRequest request) {
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

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ApiError> handleInvalidPaymentState(
            InvalidPaymentStateException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "INVALID_PAYMENT_STATE",
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(PaymentWindowExpiredException.class)
    public ResponseEntity<ApiError> handlePaymentWindowExpired(
            PaymentWindowExpiredException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "PAYMENT_WINDOW_EXPIRED",
                        ex.getMessage(),
                        422,
                        Instant.now(clock).toString(),
                        correlationId));
    }

    @ExceptionHandler(InsufficientRefundableAmountException.class)
    public ResponseEntity<ApiError> handleInsufficientRefundableAmount(
            InsufficientRefundableAmountException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(422)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "INSUFFICIENT_REFUNDABLE_AMOUNT",
                        ex.getMessage(),
                        422,
                        Instant.now(clock).toString(),
                        correlationId));
    }

    // #1694: the blanket `@ExceptionHandler(IllegalArgumentException.class)` (400 BAD_REQUEST)
    // that lived here is deleted, not replaced. The one remaining IllegalArgumentException throw
    // reachable through this advice's controllers — PaymentReversalServiceImpl.
    // refundPartyStandalone's "partyId must not be blank" — is dead code from HTTP: partyId
    // already carries @NotBlank on PartyStandaloneRefundRequest, so bean validation rejects a
    // blank value with a 400 (via pos-web-common's GlobalApiExceptionHandler) before this
    // method ever runs; see the comment at that throw site. With nothing genuine left to map,
    // deleting the blanket handler simply lets a true server-side IllegalArgumentException
    // (Hibernate/JPA, UUID.fromString, ...) fall through to the platform's correlated 500
    // fallback instead of being mis-reported as a client 400.

    @ExceptionHandler(PaymentGatewayException.class)
    public ResponseEntity<ApiError> handlePaymentGatewayException(
            PaymentGatewayException ex, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        "INTERNAL_SERVER_ERROR",
                        ex.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
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

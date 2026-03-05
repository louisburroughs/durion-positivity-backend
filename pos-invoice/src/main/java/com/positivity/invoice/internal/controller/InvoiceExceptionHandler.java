package com.positivity.invoice.internal.controller;

import java.time.Clock;

import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

@RestControllerAdvice(assignableTypes = InvoiceController.class)
@RequiredArgsConstructor
public class InvoiceExceptionHandler {
        private final Clock clock;

        private static final String X_CORRELATION_ID = "X-Correlation-Id";
        private static final String MESSAGE = "message";
        private static final String ERROR = "error";
        private static final String TIMESTAMP = "timestamp";
        private static final String CODE = "code";
        private static final String STATUS = "status";
        private static final String CORRELATION_ID = "correlationId";

        @ExceptionHandler(InvoiceNotFoundException.class)
        public ResponseEntity<Map<String, Object>> handleInvoiceNotFound(
                        InvoiceNotFoundException ex, HttpServletRequest request) {
                String correlationId = correlationId(request);
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .header(X_CORRELATION_ID, correlationId)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now(clock).toString(),
                                                ERROR, "NOT_FOUND",
                                                CODE, "NOT_FOUND",
                                                STATUS, HttpStatus.NOT_FOUND.value(),
                                                CORRELATION_ID, correlationId,
                                                MESSAGE, ex.getMessage()));
        }

        @ExceptionHandler(InvalidInvoiceStateException.class)
        public ResponseEntity<Map<String, Object>> handleInvalidInvoiceState(
                        InvalidInvoiceStateException ex, HttpServletRequest request) {
                String correlationId = correlationId(request);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .header(X_CORRELATION_ID, correlationId)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now(clock).toString(),
                                                ERROR, "INVALID_STATE",
                                                CODE, "INVALID_STATE",
                                                STATUS, HttpStatus.CONFLICT.value(),
                                                CORRELATION_ID, correlationId,
                                                MESSAGE, ex.getMessage()));
        }

        /**
         * C3 (ADR-0017): {@link IllegalStateException} maps to HTTP 409 Conflict.
         * Prevents "already finalized" and "POSTED" state errors from returning 500.
         */
        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Map<String, Object>> handleIllegalState(
                        IllegalStateException ex, HttpServletRequest request) {
                String correlationId = correlationId(request);
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .header(X_CORRELATION_ID, correlationId)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now(clock).toString(),
                                                ERROR, "CONFLICT",
                                                CODE, "CONFLICT",
                                                STATUS, HttpStatus.CONFLICT.value(),
                                                CORRELATION_ID, correlationId,
                                                MESSAGE, ex.getMessage()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, Object>> handleIllegalArgument(
                        IllegalArgumentException ex, HttpServletRequest request) {
                String correlationId = correlationId(request);
                String msg = ex.getMessage() != null ? ex.getMessage() : "";
                List<Map<String, Object>> fieldErrors = msg.toLowerCase().contains("approval code")
                                ? List.of(Map.of("field", "managerApprovalCode", MESSAGE, msg))
                                : List.of();
                java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                body.put(TIMESTAMP, Instant.now(clock).toString());
                body.put(ERROR, "VALIDATION_ERROR");
                body.put(CODE, "VALIDATION_ERROR");
                body.put(STATUS, HttpStatus.BAD_REQUEST.value());
                body.put(CORRELATION_ID, correlationId);
                body.put(MESSAGE, msg);
                body.put("fieldErrors", fieldErrors);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .header(X_CORRELATION_ID, correlationId)
                                .body(body);
        }

        private static String correlationId(HttpServletRequest request) {
                String header = request.getHeader(X_CORRELATION_ID);
                return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
        }
}

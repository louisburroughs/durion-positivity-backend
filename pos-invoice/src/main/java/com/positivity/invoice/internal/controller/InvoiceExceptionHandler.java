package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice(assignableTypes = InvoiceController.class)
public class InvoiceExceptionHandler {

        private static final String MESSAGE = "message";
        private static final String ERROR = "error";
        private static final String TIMESTAMP = "timestamp";
        private static final String CODE = "code";
        private static final String STATUS = "status";
        private static final String CORRELATION_ID = "correlationId";

        @ExceptionHandler(InvoiceNotFoundException.class)
        public ResponseEntity<Map<String, Object>> handleInvoiceNotFound(
                        InvoiceNotFoundException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "NOT_FOUND",
                                                CODE, "NOT_FOUND",
                                                STATUS, HttpStatus.NOT_FOUND.value(),
                                                CORRELATION_ID, correlationId(request),
                                                MESSAGE, ex.getMessage()));
        }

        @ExceptionHandler(InvalidInvoiceStateException.class)
        public ResponseEntity<Map<String, Object>> handleInvalidInvoiceState(
                        InvalidInvoiceStateException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "INVALID_STATE",
                                                CODE, "INVALID_STATE",
                                                STATUS, HttpStatus.CONFLICT.value(),
                                                CORRELATION_ID, correlationId(request),
                                                MESSAGE, ex.getMessage()));
        }

        /**
         * C3 (ADR-0017): {@link IllegalStateException} maps to HTTP 409 Conflict.
         * Prevents "already finalized" and "POSTED" state errors from returning 500.
         */
        @ExceptionHandler(IllegalStateException.class)
        public ResponseEntity<Map<String, Object>> handleIllegalState(
                        IllegalStateException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "CONFLICT",
                                                CODE, "CONFLICT",
                                                STATUS, HttpStatus.CONFLICT.value(),
                                                CORRELATION_ID, correlationId(request),
                                                MESSAGE, ex.getMessage()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, Object>> handleIllegalArgument(
                        IllegalArgumentException ex, HttpServletRequest request) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "VALIDATION_ERROR",
                                                CODE, "VALIDATION_ERROR",
                                                STATUS, HttpStatus.BAD_REQUEST.value(),
                                                CORRELATION_ID, correlationId(request),
                                                MESSAGE, ex.getMessage()));
        }

        private static String correlationId(HttpServletRequest request) {
                String header = request.getHeader("X-Correlation-Id");
                return (header != null && !header.isBlank()) ? header : UUID.randomUUID().toString();
        }
}

package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InvalidInvoiceStateException;
import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(assignableTypes = InvoiceController.class)
public class InvoiceExceptionHandler {

        private static final String MESSAGE = "message";
        private static final String ERROR = "error";
        private static final String TIMESTAMP = "timestamp";

        @ExceptionHandler(InvoiceNotFoundException.class)
        public ResponseEntity<Map<String, Object>> handleInvoiceNotFound(InvoiceNotFoundException ex) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "NOT_FOUND",
                                                MESSAGE, ex.getMessage()));
        }

        @ExceptionHandler(InvalidInvoiceStateException.class)
        public ResponseEntity<Map<String, Object>> handleInvalidInvoiceState(InvalidInvoiceStateException ex) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "INVALID_STATE",
                                                MESSAGE, ex.getMessage()));
        }

        @ExceptionHandler(IllegalArgumentException.class)
        public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                                .body(Map.of(
                                                TIMESTAMP, Instant.now().toString(),
                                                ERROR, "VALIDATION_ERROR",
                                                MESSAGE, ex.getMessage()));
        }
}

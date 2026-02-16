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

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleInvoiceNotFound(InvoiceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "error", "NOT_FOUND",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(InvalidInvoiceStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidInvoiceState(InvalidInvoiceStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "error", "INVALID_STATE",
                        "message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "timestamp", Instant.now().toString(),
                        "error", "VALIDATION_ERROR",
                        "message", ex.getMessage()));
    }
}

package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NltiController.class)
class NltiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(
                Map.of("status", "ERROR", "code", "VALIDATION_ERROR",
                        "correlationId", UUIDv7Generator.generate().toString(),
                        "details", details));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(
                Map.of("status", "ERROR", "code", "VALIDATION_ERROR",
                        "correlationId", UUIDv7Generator.generate().toString(),
                        "details", List.of(ex.getMessage())));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<Map<String, Object>> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                Map.of("status", "ERROR", "code", "RATE_LIMIT_EXCEEDED",
                        "correlationId", UUIDv7Generator.generate().toString()));
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    ResponseEntity<Map<String, Object>> handleUnsupported(UnsupportedOperationException ex) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
                Map.of("status", "ERROR", "code", "NOT_IMPLEMENTED",
                        "correlationId", UUIDv7Generator.generate().toString()));
    }
}

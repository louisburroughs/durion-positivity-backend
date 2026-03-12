package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.RateLimitExceededException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NltiController.class)
class NltiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", List.of(ex.getMessage()), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<Map<String, Object>> handleRateLimit(
            RateLimitExceededException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", request);
    }

    @ExceptionHandler(SessionOwnershipViolationException.class)
    ResponseEntity<Map<String, Object>> handleSessionOwnershipViolation(
            SessionOwnershipViolationException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.FORBIDDEN, "SESSION_ACCESS_DENIED", request);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    ResponseEntity<Map<String, Object>> handleUnsupported(
            UnsupportedOperationException ex,
            HttpServletRequest request) {
        return errorResponse(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", request);
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status,
            String code,
            HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(status)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(Map.of(
                        "status", "ERROR",
                        "code", code,
                        "correlationId", correlationId.toString()));
    }

    private ResponseEntity<Map<String, Object>> errorResponse(
            HttpStatus status,
            String code,
            List<String> details,
            HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.status(status)
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(Map.of(
                        "status", "ERROR",
                        "code", code,
                        "correlationId", correlationId.toString(),
                        "details", details));
    }
}

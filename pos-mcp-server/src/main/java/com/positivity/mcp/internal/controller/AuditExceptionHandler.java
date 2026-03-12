package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.InvalidAuditEventTypeException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuditController.class)
class AuditExceptionHandler {

    @ExceptionHandler(InvalidAuditEventTypeException.class)
    ResponseEntity<Map<String, Object>> handleInvalidAuditEventType(InvalidAuditEventTypeException ex) {
        return ResponseEntity.badRequest().body(
                Map.of(
                        "status", "ERROR",
                        "code", "INVALID_EVENT_TYPE",
                        "message", ex.getMessage()));
    }
}

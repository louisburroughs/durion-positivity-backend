package com.positivity.mcp.internal.controller;

import com.positivity.mcp.internal.exception.InvalidAuditEventTypeException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuditController.class)
class AuditExceptionHandler {

    @ExceptionHandler(InvalidAuditEventTypeException.class)
    ResponseEntity<Map<String, Object>> handleInvalidAuditEventType(
            InvalidAuditEventTypeException ex, HttpServletRequest request) {
        return respond("INVALID_EVENT_TYPE", ex.getMessage(), request);
    }

    /**
     * Builds the error response, carrying the correlation id in the {@code X-Correlation-Id}
     * response header (ADR-0017 §4, issue #1729). Sole path building a response here, so a
     * handler added later cannot forget the header.
     */
    private ResponseEntity<Map<String, Object>> respond(String code, String message, HttpServletRequest request) {
        UUID correlationId = NltiCorrelationIdSupport.resolveFromRequest(request);
        return ResponseEntity.badRequest()
                .header(NltiCorrelationIdSupport.CORRELATION_ID_HEADER, correlationId.toString())
                .body(Map.of(
                        "status", "ERROR",
                        "code", code,
                        "message", message));
    }
}

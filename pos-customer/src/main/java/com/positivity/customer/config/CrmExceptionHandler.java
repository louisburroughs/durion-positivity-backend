package com.positivity.customer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import java.time.Instant;

/**
 * Centralized exception handler for CRM service.
 *
 * Intercepts AccessDeniedException and returns a standardized 403 response
 * with minimal, audit-friendly fields.
 */
@ControllerAdvice
@Slf4j
public class CrmExceptionHandler {

    public record ErrorResponse(String errorCode, String message, String path, Instant timestamp) {
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
        String path = request != null ? request.getDescription(false).replace("uri=", "") : "";
        log.warn("Access denied on {}: {}", path, ex.getMessage());

        ErrorResponse body = new ErrorResponse(
                "PERMISSION_DENIED",
                "You do not have permission to perform this action",
                path,
                Instant.now());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}

package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.shared.error.ApiError;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.positivity.catalog.internal.controller")
public class CatalogExceptionHandler {

    private String getOrCreateCorrelationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        return correlationId;
    }

    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(CatalogNotFoundException ex, HttpServletRequest request) {
        String correlationId = getOrCreateCorrelationId(request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("NOT_FOUND", ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(CatalogForbiddenOperationException.class)
    public ResponseEntity<ApiError> handleForbidden(CatalogForbiddenOperationException ex, HttpServletRequest request) {
        String correlationId = getOrCreateCorrelationId(request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("FORBIDDEN", ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(CatalogValidationException.class)
    public ResponseEntity<ApiError> handleBadRequest(CatalogValidationException ex, HttpServletRequest request) {
        String correlationId = getOrCreateCorrelationId(request);
        return ResponseEntity.badRequest()
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("VALIDATION_ERROR", ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(CatalogBusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessConflict(CatalogBusinessRuleException ex, HttpServletRequest request) {
        String correlationId = getOrCreateCorrelationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("BUSINESS_RULE_VIOLATION", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, OptimisticLockException.class })
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex, HttpServletRequest request) {
        String correlationId = getOrCreateCorrelationId(request);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("CONFLICT",
                        "Resource was updated concurrently. Please retry.",
                        HttpStatus.CONFLICT.value(), Instant.now().toString(), correlationId));
    }

    @ExceptionHandler(ServletException.class)
    public ResponseEntity<ApiError> handleServletException(ServletException ex, HttpServletRequest request) {
        String correlationId = getOrCreateCorrelationId(request);
        Throwable cause = ex.getCause();
        if (cause instanceof OptimisticLockException || cause instanceof ObjectOptimisticLockingFailureException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header("X-Correlation-Id", correlationId)
                    .body(ApiError.of("CONFLICT",
                            "Resource was updated concurrently. Please retry.",
                            HttpStatus.CONFLICT.value(), Instant.now().toString(), correlationId));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("X-Correlation-Id", correlationId)
                .body(ApiError.of("INTERNAL_ERROR", ex.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now().toString(), correlationId));
    }
}

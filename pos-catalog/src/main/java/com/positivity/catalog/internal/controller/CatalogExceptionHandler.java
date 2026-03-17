package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.shared.error.ApiError;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.ServletException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.positivity.catalog.internal.controller")
public class CatalogExceptionHandler {

    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(CatalogNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("NOT_FOUND", ex.getMessage(),
                        HttpStatus.NOT_FOUND.value(), Instant.now().toString(), null));
    }

    @ExceptionHandler(CatalogForbiddenOperationException.class)
    public ResponseEntity<ApiError> handleForbidden(CatalogForbiddenOperationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("FORBIDDEN", ex.getMessage(),
                        HttpStatus.FORBIDDEN.value(), Instant.now().toString(), null));
    }

    @ExceptionHandler(CatalogValidationException.class)
    public ResponseEntity<ApiError> handleBadRequest(CatalogValidationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("VALIDATION_ERROR", ex.getMessage(),
                        HttpStatus.BAD_REQUEST.value(), Instant.now().toString(), null));
    }

    @ExceptionHandler(CatalogBusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessConflict(CatalogBusinessRuleException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("BUSINESS_RULE_VIOLATION", ex.getMessage(),
                        HttpStatus.CONFLICT.value(), Instant.now().toString(), null));
    }

    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, OptimisticLockException.class })
    public ResponseEntity<ApiError> handleConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("CONFLICT",
                        "Resource was updated concurrently. Please retry.",
                        HttpStatus.CONFLICT.value(), Instant.now().toString(), null));
    }

    @ExceptionHandler(ServletException.class)
    public ResponseEntity<ApiError> handleServletException(ServletException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof OptimisticLockException || cause instanceof ObjectOptimisticLockingFailureException) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiError.of("CONFLICT",
                            "Resource was updated concurrently. Please retry.",
                            HttpStatus.CONFLICT.value(), Instant.now().toString(), null));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("INTERNAL_ERROR", ex.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), Instant.now().toString(), null));
    }
}

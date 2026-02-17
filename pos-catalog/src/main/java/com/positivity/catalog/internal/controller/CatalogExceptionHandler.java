package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogForbiddenOperationException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.positivity.catalog.internal.controller")
public class CatalogExceptionHandler {

    @ExceptionHandler(CatalogNotFoundException.class)
    public ResponseEntity<String> handleNotFound(CatalogNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ex.getMessage());
    }

    @ExceptionHandler(CatalogForbiddenOperationException.class)
    public ResponseEntity<String> handleForbidden(CatalogForbiddenOperationException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ex.getMessage());
    }

    @ExceptionHandler({ CatalogValidationException.class, CatalogBusinessRuleException.class })
    public ResponseEntity<String> handleBadRequest(RuntimeException ex) {
        return ResponseEntity.badRequest().body(ex.getMessage());
    }

    @ExceptionHandler({ ObjectOptimisticLockingFailureException.class, OptimisticLockException.class })
    public ResponseEntity<String> handleConflict(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body("CONFLICT: Resource was updated concurrently. Please retry.");
    }
}

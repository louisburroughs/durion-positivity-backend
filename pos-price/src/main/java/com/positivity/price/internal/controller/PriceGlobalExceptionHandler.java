package com.positivity.price.internal.controller;

import com.positivity.price.internal.dto.ErrorResponse;
import com.positivity.price.internal.exception.ProductNotFoundException;
import com.positivity.price.internal.exception.SnapshotNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for price module controllers.
 *
 * Issue: #50
 */
@RestControllerAdvice
public class PriceGlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProductNotFound(ProductNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        "PRODUCT_NOT_FOUND",
                        exception.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now()));
    }

    @ExceptionHandler(SnapshotNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSnapshotNotFound(SnapshotNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(
                        "SNAPSHOT_NOT_FOUND",
                        exception.getMessage(),
                        HttpStatus.NOT_FOUND.value(),
                        Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationError(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("Validation failed");

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        "VALIDATION_ERROR",
                        message,
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now()));
    }
}

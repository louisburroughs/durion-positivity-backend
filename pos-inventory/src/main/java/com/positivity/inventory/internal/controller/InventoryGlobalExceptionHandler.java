package com.positivity.inventory.internal.controller;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Module-wide exception advice for inventory controllers.
 */
@RestControllerAdvice(basePackages = "com.positivity.inventory.internal.controller")
@Slf4j
@RequiredArgsConstructor
public class InventoryGlobalExceptionHandler {

    private static final String SKU_ID = "skuId";
    private static final String LOCATION_ID = "locationId";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String TIMESTAMP = "timestamp";
    private static final String CODE = "code";
    private static final String MESSAGE = "message";
    private final Clock clock;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationError(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + " " + fieldError.getDefaultMessage())
                .orElse("Validation failed");

        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, message);
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            ConstraintViolationException.class,
            InvalidInventoryAvailabilityRequestException.class,
            IllegalArgumentException.class
    })
    public ResponseEntity<Map<String, String>> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTaskNotFound(TaskNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CycleCountPlanNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCycleCountPlanNotFound(CycleCountPlanNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler({ ProductNotFoundException.class, LocationNotFoundException.class })
    public ResponseEntity<Map<String, String>> handleResourceNotFound(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidCountQuantityException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCountQuantity(InvalidCountQuantityException ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientStock(InsufficientStockException ex) {
        return build(HttpStatus.valueOf(422), "INSUFFICIENT_STOCK", ex.getMessage());
    }

    @ExceptionHandler(RecountLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleRecountLimitExceeded(RecountLimitExceededException ex) {
        return build(
                HttpStatus.BAD_REQUEST,
                "RECOUNT_LIMIT_EXCEEDED",
                ex.getMessage(),
                Map.of(
                        "taskId", ex.getTaskId().toString(),
                        "currentCount", ex.getCurrentCount(),
                        "maxAllowed", ex.getMaxAllowed()));
    }

    @ExceptionHandler(InsufficientPermissionException.class)
    public ResponseEntity<Map<String, String>> handleInsufficientPermission(InsufficientPermissionException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(AdjustmentLedgerPostingException.class)
    public ResponseEntity<Map<String, Object>> handleAdjustmentLedgerPosting(AdjustmentLedgerPostingException ex) {
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "ADJUSTMENT_LEDGER_POST_FAILED",
                ex.getMessage(),
                Map.of("adjustmentId", ex.getAdjustmentId() != null ? ex.getAdjustmentId().toString() : ""));
    }

    @ExceptionHandler({
            LocationNotValidForSkuException.class,
            LocationAtCapacityException.class,
            NoOnHandAtSourceLocationException.class,
            PutawayValidationException.class
    })
    public ResponseEntity<Map<String, Object>> handlePutawayValidation(PutawayValidationException ex) {
        return build(
                HttpStatus.valueOf(422),
                ex.getErrorCode(),
                ex.getMessage(),
                putawayContext(ex));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception ex) {
        log.error("Unhandled inventory exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error occurred");
    }

    private ResponseEntity<Map<String, String>> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        CODE, code,
                        MESSAGE, message != null ? message : ""));
    }

    private ResponseEntity<Map<String, Object>> build(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> extra) {
        return ResponseEntity.status(status)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        CODE, code,
                        MESSAGE, message != null ? message : "",
                        "context", extra));
    }

    private Map<String, Object> putawayContext(PutawayValidationException ex) {
        if (ex instanceof LocationNotValidForSkuException specific) {
            return Map.of(
                    LOCATION_ID, safe(specific.getLocationId()),
                    SKU_ID, safe(specific.getSkuId()),
                    "reason", safe(specific.getReason()));
        }
        if (ex instanceof LocationAtCapacityException specific) {
            return Map.of(
                    LOCATION_ID, safe(specific.getLocationId()),
                    "currentCapacity", specific.getCurrentCapacity(),
                    "maxCapacity", specific.getMaxCapacity());
        }
        if (ex instanceof NoOnHandAtSourceLocationException specific) {
            return Map.of(
                    LOCATION_ID, safe(specific.getLocationId()),
                    SKU_ID, safe(specific.getSkuId()));
        }
        return Map.of();
    }

    private String safe(UUID value) {
        return value == null ? "" : value.toString();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

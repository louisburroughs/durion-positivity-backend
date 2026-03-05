package com.positivity.inventory.internal.controller;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.exception.PurchaseOrderNotApprovedException;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.shared.id.UUIDv7Generator;

import jakarta.servlet.http.HttpServletRequest;
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
    private static final String STATUS = "status";
    private static final String CORRELATION_ID = "correlationId";
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(MethodArgumentNotValidException ex) {
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
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(PurchaseOrderNotApprovedException.class)
    public ResponseEntity<Map<String, Object>> handlePurchaseOrderNotApproved(PurchaseOrderNotApprovedException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(InvalidPoReferenceException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPoReference(InvalidPoReferenceException ex) {
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(DuplicateAsnException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateAsn(DuplicateAsnException ex) {
        return build(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(OverReceiptNotPermittedException.class)
    public ResponseEntity<Map<String, Object>> handleOverReceiptNotPermitted(OverReceiptNotPermittedException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTaskNotFound(TaskNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(CycleCountPlanNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleCycleCountPlanNotFound(CycleCountPlanNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler({ ResourceNotFoundException.class, ProductNotFoundException.class,
            LocationNotFoundException.class })
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(InvalidCountQuantityException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidCountQuantity(InvalidCountQuantityException ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(InsufficientStockException ex) {
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
    public ResponseEntity<Map<String, Object>> handleInsufficientPermission(InsufficientPermissionException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage());
    }

    @ExceptionHandler(InsufficientAtpException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientAtp(InsufficientAtpException ex) {
        return build(HttpStatus.valueOf(422), "INSUFFICIENT_ATP", ex.getMessage());
    }

    @ExceptionHandler(PickScanMismatchException.class)
    public ResponseEntity<Map<String, Object>> handlePickScanMismatch(PickScanMismatchException ex) {
        return build(HttpStatus.valueOf(422), "PICK_SCAN_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(WorkorderConsumptionException.class)
    public ResponseEntity<Map<String, Object>> handleWorkorderConsumption(WorkorderConsumptionException ex) {
        return build(HttpStatus.valueOf(422), "WORKORDER_CONSUMPTION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(ReturnQuantityExceededException.class)
    public ResponseEntity<Map<String, Object>> handleReturnQuantityExceeded(ReturnQuantityExceededException ex) {
        return build(HttpStatus.valueOf(422), "RETURN_QUANTITY_EXCEEDED", ex.getMessage());
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

    @ExceptionHandler({
            SourceDocumentNotFoundException.class,
            ReceivingSessionNotFoundException.class
    })
    public ResponseEntity<Map<String, Object>> handleReceivingNotFound(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(SourceDocumentAlreadyReceivedException.class)
    public ResponseEntity<Map<String, Object>> handleSourceDocumentAlreadyReceived(
            SourceDocumentAlreadyReceivedException ex) {
        return build(HttpStatus.BAD_REQUEST, "SOURCE_DOCUMENT_ALREADY_RECEIVED", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied");
    }

    @ExceptionHandler(WorkorderClosedException.class)
    public ResponseEntity<Map<String, Object>> handleWorkorderClosed(WorkorderClosedException ex) {
        return build(HttpStatus.BAD_REQUEST, "WORKORDER_CLOSED", ex.getMessage());
    }

    @ExceptionHandler(PartMatchPermissionException.class)
    public ResponseEntity<Map<String, Object>> handlePartMatchPermission(PartMatchPermissionException ex) {
        return build(HttpStatus.FORBIDDEN, "PART_MATCH_PERMISSION_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        log.error("Unhandled inventory exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error occurred");
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message) {
        String correlationId = resolveCorrelationId(null);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(Map.of(
                        TIMESTAMP, Instant.now(clock).toString(),
                        STATUS, status.value(),
                        CORRELATION_ID, correlationId,
                        CODE, code,
                        MESSAGE, message != null ? message : ""));
    }

    private ResponseEntity<Map<String, Object>> build(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> extra) {
        String correlationId = resolveCorrelationId(null);
        Map<String, Object> body = new HashMap<>();
        body.put(TIMESTAMP, Instant.now(clock).toString());
        body.put(STATUS, status.value());
        body.put(CORRELATION_ID, correlationId);
        body.put(CODE, code);
        body.put(MESSAGE, message != null ? message : "");
        body.put("context", extra);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(body);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request != null) {
            String fromRequest = request.getHeader(X_CORRELATION_ID);
            if (fromRequest != null && !fromRequest.isBlank()) {
                return fromRequest;
            }
        }
        var attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletRequestAttributes) {
            String fromContext = servletRequestAttributes.getRequest().getHeader(X_CORRELATION_ID);
            if (fromContext != null && !fromContext.isBlank()) {
                return fromContext;
            }
        }
        return UUIDv7Generator.generate().toString();
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

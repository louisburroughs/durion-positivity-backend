package com.positivity.inventory.internal.controller;

import com.positivity.inventory.internal.exception.AdjustmentLedgerPostingException;
import com.positivity.inventory.internal.exception.AsOfInFutureException;
import com.positivity.inventory.internal.exception.CrossSiteTransferRequiresOrderException;
import com.positivity.inventory.internal.exception.CycleCountConflictException;
import com.positivity.inventory.internal.exception.CycleCountPlanNotFoundException;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.exception.FractionalQuantityNotAllowedException;
import com.positivity.inventory.internal.exception.InsufficientAtpException;
import com.positivity.inventory.internal.exception.InsufficientPermissionException;
import com.positivity.inventory.internal.exception.InsufficientStockException;
import com.positivity.inventory.internal.exception.InvalidCountQuantityException;
import com.positivity.inventory.internal.exception.InvalidInventoryAvailabilityRequestException;
import com.positivity.inventory.internal.exception.InvalidParamCombinationException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.LocationAtCapacityException;
import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationNotValidForSkuException;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import com.positivity.inventory.internal.exception.LotInsufficientStockException;
import com.positivity.inventory.internal.exception.LotNotAvailableException;
import com.positivity.inventory.internal.exception.LotNumberRequiredException;
import com.positivity.inventory.internal.exception.LotUnknownException;
import com.positivity.inventory.internal.exception.NegativeStockPolicyViolationException;
import com.positivity.inventory.internal.exception.NoOnHandAtSourceLocationException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
import com.positivity.inventory.internal.exception.ProductNotFoundException;
import com.positivity.inventory.internal.exception.PurchaseSuggestionConversionException;
import com.positivity.inventory.internal.exception.PurchaseSuggestionStateException;
import com.positivity.inventory.internal.exception.PutawayValidationException;
import com.positivity.inventory.internal.exception.ReceivingSessionNotFoundException;
import com.positivity.inventory.internal.exception.RecountLimitExceededException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.exception.ReturnQuantityExceededException;
import com.positivity.inventory.internal.exception.RollupExpansionTooLargeException;
import com.positivity.inventory.internal.exception.ScrapInsufficientStockException;
import com.positivity.inventory.internal.exception.ScrapLedgerPostingException;
import com.positivity.inventory.internal.exception.ScrapNotFoundException;
import com.positivity.inventory.internal.exception.SerialAlreadyInStockException;
import com.positivity.inventory.internal.exception.SerialCountMismatchException;
import com.positivity.inventory.internal.exception.SerialNotAvailableException;
import com.positivity.inventory.internal.exception.ShortageResolutionException;
import com.positivity.inventory.internal.exception.SnoozeUntilNotInFutureException;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentLinesUnavailableException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.internal.exception.TaskNotFoundException;
import com.positivity.inventory.internal.exception.TransferLocationNotEligibleException;
import com.positivity.inventory.internal.exception.TransferOrderNotFoundException;
import com.positivity.inventory.internal.exception.TransferQuantityExceededException;
import com.positivity.inventory.internal.exception.UnsupportedSourceDocumentTypeException;
import com.positivity.inventory.internal.exception.UomConversionUndefinedException;
import com.positivity.inventory.internal.exception.ValuationAsOfSkuCapExceededException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.internal.exception.WorkorderConsumptionException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Module-wide exception advice for inventory controllers.
 */
@RestControllerAdvice(basePackages = "com.positivity.inventory.internal.controller")
@Slf4j
@RequiredArgsConstructor
public class InventoryGlobalExceptionHandler {

    private static final String NOT_FOUND = "NOT_FOUND";
    private static final String FORBIDDEN = "FORBIDDEN";
    private static final String CONFLICT = "CONFLICT";
    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationError(MethodArgumentNotValidException ex) {
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
    public ResponseEntity<ApiError> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidPoReferenceException.class)
    public ResponseEntity<ApiError> handleInvalidPoReference(InvalidPoReferenceException ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(DuplicateAsnException.class)
    public ResponseEntity<ApiError> handleDuplicateAsn(DuplicateAsnException ex) {
        return build(HttpStatus.CONFLICT, CONFLICT, ex.getMessage());
    }

    /**
     * A second enabled ANY putaway rule was requested (#1514). ANY matches every line, so only the
     * first one the priority order reaches can ever fire; a second is unreachable configuration
     * rather than a bad request, hence 409.
     */
    @ExceptionHandler(DuplicateEnabledAnyPutawayRuleException.class)
    public ResponseEntity<ApiError> handleDuplicateEnabledAnyPutawayRule(DuplicateEnabledAnyPutawayRuleException ex) {
        return build(HttpStatus.CONFLICT, DuplicateEnabledAnyPutawayRuleException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(OverReceiptNotPermittedException.class)
    public ResponseEntity<ApiError> handleOverReceiptNotPermitted(OverReceiptNotPermittedException ex) {
        return build(HttpStatus.valueOf(422), OverReceiptNotPermittedException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(TaskNotFoundException.class)
    public ResponseEntity<ApiError> handleTaskNotFound(TaskNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(CycleCountPlanNotFoundException.class)
    public ResponseEntity<ApiError> handleCycleCountPlanNotFound(CycleCountPlanNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({ResourceNotFoundException.class, ProductNotFoundException.class, LocationNotFoundException.class
    })
    public ResponseEntity<ApiError> handleResourceNotFound(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(LocationServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleLocationServiceUnavailable(LocationServiceUnavailableException ex) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "LOCATION_SERVICE_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(RollupExpansionTooLargeException.class)
    public ResponseEntity<ApiError> handleRollupExpansionTooLarge(RollupExpansionTooLargeException ex) {
        return build(HttpStatus.valueOf(422), "ROLLUP_EXPANSION_TOO_LARGE", ex.getMessage());
    }

    @ExceptionHandler(InvalidCountQuantityException.class)
    public ResponseEntity<ApiError> handleInvalidCountQuantity(InvalidCountQuantityException ex) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, ex.getMessage());
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ApiError> handleInsufficientStock(InsufficientStockException ex) {
        return build(HttpStatus.valueOf(422), "INSUFFICIENT_STOCK", ex.getMessage());
    }

    @ExceptionHandler(NegativeStockPolicyViolationException.class)
    public ResponseEntity<ApiError> handleNegativeStockPolicyViolation(NegativeStockPolicyViolationException ex) {
        // odoo-parity K1 (#1027): deterministic per-case code from the exception
        // (NEGATIVE_STOCK_OVERRIDE_REQUIRED / NEGATIVE_STOCK_FLOOR_VIOLATION).
        return build(HttpStatus.valueOf(422), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(CycleCountConflictException.class)
    public ResponseEntity<ApiError> handleCycleCountConflict(CycleCountConflictException ex) {
        // odoo-parity I2 (#1026): approval rejected — task flagged CONFLICT,
        // reviewer must explicitly choose recount or recomputed approval.
        return build(HttpStatus.CONFLICT, "CYCLE_COUNT_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(RecountLimitExceededException.class)
    public ResponseEntity<ApiError> handleRecountLimitExceeded(RecountLimitExceededException ex) {
        return build(HttpStatus.BAD_REQUEST, "RECOUNT_LIMIT_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(AsOfInFutureException.class)
    public ResponseEntity<ApiError> handleAsOfInFuture(AsOfInFutureException ex) {
        // odoo-parity A3 (#1029): deterministic 422 for future-dated point-in-time queries.
        return build(HttpStatus.valueOf(422), "AS_OF_IN_FUTURE", ex.getMessage());
    }

    @ExceptionHandler(ValuationAsOfSkuCapExceededException.class)
    public ResponseEntity<ApiError> handleValuationAsOfSkuCapExceeded(ValuationAsOfSkuCapExceededException ex) {
        return build(HttpStatus.valueOf(422), ValuationAsOfSkuCapExceededException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(SnoozeUntilNotInFutureException.class)
    public ResponseEntity<ApiError> handleSnoozeUntilNotInFuture(SnoozeUntilNotInFutureException ex) {
        // odoo-parity F3 (#1041): deterministic 422 for non-future snooze instants.
        return build(HttpStatus.valueOf(422), SnoozeUntilNotInFutureException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(InsufficientPermissionException.class)
    public ResponseEntity<ApiError> handleInsufficientPermission(InsufficientPermissionException ex) {
        return build(HttpStatus.FORBIDDEN, FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(InsufficientAtpException.class)
    public ResponseEntity<ApiError> handleInsufficientAtp(InsufficientAtpException ex) {
        return build(HttpStatus.valueOf(422), "INSUFFICIENT_ATP", ex.getMessage());
    }

    @ExceptionHandler(PickScanMismatchException.class)
    public ResponseEntity<ApiError> handlePickScanMismatch(PickScanMismatchException ex) {
        return build(HttpStatus.valueOf(422), "PICK_SCAN_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(WorkorderConsumptionException.class)
    public ResponseEntity<ApiError> handleWorkorderConsumption(WorkorderConsumptionException ex) {
        return build(HttpStatus.valueOf(422), "WORKORDER_CONSUMPTION_ERROR", ex.getMessage());
    }

    @ExceptionHandler(ReturnQuantityExceededException.class)
    public ResponseEntity<ApiError> handleReturnQuantityExceeded(ReturnQuantityExceededException ex) {
        return build(HttpStatus.valueOf(422), "RETURN_QUANTITY_EXCEEDED", ex.getMessage());
    }

    @ExceptionHandler(AdjustmentLedgerPostingException.class)
    public ResponseEntity<ApiError> handleAdjustmentLedgerPosting(AdjustmentLedgerPostingException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "ADJUSTMENT_LEDGER_POST_FAILED", ex.getMessage());
    }

    @ExceptionHandler(TransferQuantityExceededException.class)
    public ResponseEntity<ApiError> handleTransferQuantityExceeded(TransferQuantityExceededException ex) {
        // odoo-parity C2 (#1036): deterministic per-bound 422
        // (TRANSFER_DISPATCH_EXCEEDS_REQUESTED / TRANSFER_RECEIVE_EXCEEDS_DISPATCHED).
        return build(HttpStatus.valueOf(422), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(CrossSiteTransferRequiresOrderException.class)
    public ResponseEntity<ApiError> handleCrossSiteTransferRequiresOrder(CrossSiteTransferRequiresOrderException ex) {
        // odoo-parity C2/spec C7 (#1036): immediate stock movements are intra-site only.
        return build(HttpStatus.valueOf(422), CrossSiteTransferRequiresOrderException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(TransferOrderNotFoundException.class)
    public ResponseEntity<ApiError> handleTransferOrderNotFound(TransferOrderNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TransferLocationNotEligibleException.class)
    public ResponseEntity<ApiError> handleTransferLocationNotEligible(TransferLocationNotEligibleException ex) {
        // odoo-parity C1/C5 (#1035): deterministic 422 for movement-ineligible sites
        // (DECISION-INVENTORY-009: INACTIVE/PENDING blocked for movement).
        return build(HttpStatus.valueOf(422), TransferLocationNotEligibleException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(ScrapNotFoundException.class)
    public ResponseEntity<ApiError> handleScrapNotFound(ScrapNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ScrapInsufficientStockException.class)
    public ResponseEntity<ApiError> handleScrapInsufficientStock(ScrapInsufficientStockException ex) {
        // odoo-parity D1 (#1030): guided-reconciliation 422 mirroring the putaway
        // source-on-hand rule (docs/putaway-validation-rules.md).
        return build(HttpStatus.valueOf(422), ScrapInsufficientStockException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(ScrapLedgerPostingException.class)
    public ResponseEntity<ApiError> handleScrapLedgerPosting(ScrapLedgerPostingException ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "SCRAP_LEDGER_POST_FAILED", ex.getMessage());
    }

    @ExceptionHandler({
        LocationNotValidForSkuException.class,
        LocationAtCapacityException.class,
        NoOnHandAtSourceLocationException.class,
        PutawayValidationException.class
    })
    public ResponseEntity<ApiError> handlePutawayValidation(PutawayValidationException ex) {
        return build(HttpStatus.valueOf(422), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler({SourceDocumentNotFoundException.class, ReceivingSessionNotFoundException.class})
    public ResponseEntity<ApiError> handleReceivingNotFound(RuntimeException ex) {
        return build(HttpStatus.NOT_FOUND, NOT_FOUND, ex.getMessage());
    }

    /**
     * A receiving session named a purchase order id absent from the projection (#1492). The header
     * and its lines are projected atomically and ADR-0044 forbids a synchronous check against
     * pos-order, so this module cannot tell replication lag from an unknown id — 409, with a
     * nextAction that says both.
     */
    @ExceptionHandler(SourceDocumentLinesUnavailableException.class)
    public ResponseEntity<ApiError> handleSourceDocumentLinesUnavailable(SourceDocumentLinesUnavailableException ex) {
        String correlationId = resolveCorrelationId(null);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.guided(
                        SourceDocumentLinesUnavailableException.ERROR_CODE,
                        ex.getMessage(),
                        HttpStatus.CONFLICT.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        null,
                        "The purchase-order line projection has not caught up. Retry in a few seconds; "
                                + "if this persists, the purchase order id is unknown.",
                        null));
    }

    /**
     * A source document type receiving cannot resolve (#1480). 422: the request is well-formed,
     * the type simply has no owning service — saying so beats a 404 that reads as "no such PO".
     */
    @ExceptionHandler(UnsupportedSourceDocumentTypeException.class)
    public ResponseEntity<ApiError> handleUnsupportedSourceDocumentType(UnsupportedSourceDocumentTypeException ex) {
        return build(
                HttpStatus.UNPROCESSABLE_ENTITY, UnsupportedSourceDocumentTypeException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(SourceDocumentAlreadyReceivedException.class)
    public ResponseEntity<ApiError> handleSourceDocumentAlreadyReceived(SourceDocumentAlreadyReceivedException ex) {
        return build(HttpStatus.BAD_REQUEST, "SOURCE_DOCUMENT_ALREADY_RECEIVED", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, FORBIDDEN, "Access denied");
    }

    @ExceptionHandler(WorkorderClosedException.class)
    public ResponseEntity<ApiError> handleWorkorderClosed(WorkorderClosedException ex) {
        return build(HttpStatus.BAD_REQUEST, "WORKORDER_CLOSED", ex.getMessage());
    }

    @ExceptionHandler(PartMatchPermissionException.class)
    public ResponseEntity<ApiError> handlePartMatchPermission(PartMatchPermissionException ex) {
        return build(HttpStatus.FORBIDDEN, "PART_MATCH_PERMISSION_REQUIRED", ex.getMessage());
    }

    @ExceptionHandler(FractionalQuantityNotAllowedException.class)
    public ResponseEntity<ApiError> handleFractionalQuantityNotAllowed(FractionalQuantityNotAllowedException ex) {
        // ADR-0055 (#1414): the posted quantity carries more decimals than the product's catalog
        // declaration allows. 422, not 400 — the request is well-formed and passes its bounds;
        // what it violates can only be known after looking the product up. Same code as
        // pos-workorder's demand-side gate (#1413), because it is the same invariant.
        return build(HttpStatus.valueOf(422), FractionalQuantityNotAllowedException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(UomConversionUndefinedException.class)
    public ResponseEntity<ApiError> handleUomConversionUndefined(UomConversionUndefinedException ex) {
        // odoo-parity B1/B4 (#1033): no conversion path to base UoM is a deterministic 422,
        // never a silent 1:1 assumption.
        return build(HttpStatus.valueOf(422), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(LotNumberRequiredException.class)
    public ResponseEntity<ApiError> handleLotNumberRequired(LotNumberRequiredException ex) {
        // odoo-parity E1 (#1038): a receipt of a LOT-tracked product without a lot number is a
        // deterministic 422, never a silently untracked posting. Since E2 (#1042) the same
        // code gates the outbound flows (pick confirm, consumption, transfer dispatch, scrap).
        return build(HttpStatus.valueOf(422), LotNumberRequiredException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(LotUnknownException.class)
    public ResponseEntity<ApiError> handleLotUnknown(LotUnknownException ex) {
        // odoo-parity E2 (#1042): outbound flows never create lots — an unknown lot number is
        // a deterministic 422.
        return build(HttpStatus.valueOf(422), LotUnknownException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(LotNotAvailableException.class)
    public ResponseEntity<ApiError> handleLotNotAvailable(LotNotAvailableException ex) {
        // odoo-parity E2 (#1042): QUARANTINED/RECALLED/CONSUMED lots cannot leave stock.
        return build(HttpStatus.valueOf(422), LotNotAvailableException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(LotInsufficientStockException.class)
    public ResponseEntity<ApiError> handleLotInsufficientStock(LotInsufficientStockException ex) {
        // odoo-parity E2 (#1042): the per-lot negative floor is absolute — no override.
        return build(HttpStatus.valueOf(422), LotInsufficientStockException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(SerialCountMismatchException.class)
    public ResponseEntity<ApiError> handleSerialCountMismatch(SerialCountMismatchException ex) {
        // odoo-parity E4 (#1050): a serialized posting must enumerate exactly |quantity| serials —
        // a deterministic 422, the whole posting rolls back.
        return build(HttpStatus.valueOf(422), SerialCountMismatchException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(SerialAlreadyInStockException.class)
    public ResponseEntity<ApiError> handleSerialAlreadyInStock(SerialAlreadyInStockException ex) {
        return build(HttpStatus.valueOf(422), SerialAlreadyInStockException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(SerialNotAvailableException.class)
    public ResponseEntity<ApiError> handleSerialNotAvailable(SerialNotAvailableException ex) {
        // odoo-parity E4 (#1050): an outbound posting naming an unknown or already-consumed serial
        // (double-issue) is a deterministic 422.
        return build(HttpStatus.valueOf(422), SerialNotAvailableException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(PurchaseSuggestionConversionException.class)
    public ResponseEntity<ApiError> handlePurchaseSuggestionConversion(PurchaseSuggestionConversionException ex) {
        // odoo-parity F4 (#1044): deterministic per-case 422 for convert preconditions
        // (NOT_ACCEPTED / VENDOR_MISMATCH / SITE_MISMATCH / MISSING_VENDOR / MISSING_UNIT_COST).
        return build(HttpStatus.valueOf(422), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(PurchaseSuggestionStateException.class)
    public ResponseEntity<ApiError> handlePurchaseSuggestionState(PurchaseSuggestionStateException ex) {
        // odoo-parity F4 (#1044): accept/dismiss from a terminal status is a 409 conflict.
        return build(HttpStatus.CONFLICT, PurchaseSuggestionStateException.ERROR_CODE, ex.getMessage());
    }

    @ExceptionHandler(InvalidParamCombinationException.class)
    public ResponseEntity<ApiError> handleInvalidParamCombination(InvalidParamCombinationException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAM_COMBINATION", ex.getMessage());
    }

    @ExceptionHandler(ShortageResolutionException.class)
    public ResponseEntity<ApiError> handleShortageResolution(ShortageResolutionException ex) {
        // odoo-parity G2 (#1049): deterministic per-case 422 for resolve preconditions
        // (MISSING_FIELD / SUBSTITUTE_UNAVAILABLE / INVALID_IDENTIFIER).
        return build(HttpStatus.valueOf(422), ex.getErrorCode(), ex.getMessage());
    }

    // No @ExceptionHandler(Exception.class) here (issue #1768, ADR-0056 §1): Spring's
    // ExceptionHandlerExceptionResolver picks the first applicable advice bean that has ANY
    // matching handler method, not the most specific handler across advices. A blanket catch-all
    // in this module-local advice therefore swallowed every unmapped exception before
    // pos-web-common's platform-wide GlobalApiExceptionHandler could run.
    //
    // Being ApiError-shaped did not make that safe. What it cost was ADR-0056 §2's
    // DataIntegrityViolationException classification, which this advice never mapped: a
    // unique-constraint or FK collision in this module answered 500 INTERNAL_ERROR instead of
    // 409 DUPLICATE_RESOURCE. Anything not handled above now falls through to the shared advice,
    // which answers a generic, correlated 500 for the genuinely unexpected and classifies
    // integrity violations properly.
    //
    // This module was the last holdout; the six remediated in #1694 all deleted theirs.
    // GlobalExceptionHandlerEnforcementTest.noModuleShadowsTheSharedCatchAll now fails the build
    // if one comes back.

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        String correlationId = resolveCorrelationId(null);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code,
                        message != null ? message : "",
                        status.value(),
                        Instant.now(clock).toString(),
                        correlationId));
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
}

package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.AdjustmentRequestResponse;
import com.positivity.inventory.internal.dto.CreateAdjustmentRequestDto;
import com.positivity.inventory.internal.dto.RecordMovementRequest;
import com.positivity.inventory.service.StockMovementService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for recording inventory stock movements and managing adjustment
 * requests.
 *
 * Issue: CAP-215 Story #37
 */
@Slf4j
@RestController
@Tag(name = "Stock Movements", description = "Inventory ledger movement recording and adjustment endpoints")
public class StockMovementController {

    private static final String NO_CURRENT_USER = "No current user";
    private final StockMovementService stockMovementService;

    public StockMovementController(StockMovementService stockMovementService) {
        this.stockMovementService = stockMovementService;
    }

    @PostMapping("/v1/inventory/stock-movements")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:stock_movement:create"})
    @PreAuthorize("hasAuthority('inventory:stock_movement:create')")
    @EmitEvent(id = "INVENTORY_STOCK_MOVEMENT_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createStockMovement",
            summary = "Record a stock movement",
            description = """
                    Records a directional stock movement in the inventory ledger: RECEIVE posts GOODS_RECEIPT, \
                    PUT_AWAY posts PUTAWAY, PICK and ISSUE post GOODS_ISSUE, RETURN posts RETURN_TO_STOCK, and \
                    TRANSFER posts a paired TRANSFER_OUT and TRANSFER_IN.
                    Use this tool for ledger-backed inventory changes; do not use updateInventoryAvailability, \
                    which always returns 501, and do not send movementType ADJUST here — quantity corrections go \
                    through createAdjustmentRequest and approveAdjustmentRequest.
                    Preconditions: PICK and ISSUE require on-hand at fromLocationId to cover the quantity; \
                    TRANSFER is intra-site only — when both ends resolve to different sites the movement must go \
                    through a transfer order so in-transit stock is represented.
                    Required inputs: productSku, fromLocationId (UUID), movementType and a positive quantity; \
                    toLocationId is required for TRANSFER, while unitOfMeasure and sourceTransactionId are \
                    optional.
                    Emits an INVENTORY_STOCK_MOVEMENT_CREATE event and posts the entries through the ledger \
                    funnel, which updates the stock summary that availability reads.
                    Returns 201 with no body, 422 with INSUFFICIENT_STOCK when a PICK or ISSUE exceeds on-hand at \
                    the source, 422 with CROSS_SITE_TRANSFER_REQUIRES_ORDER for a cross-site TRANSFER, and 400 \
                    when toLocationId is missing for TRANSFER or movementType is ADJUST.
                    """,
            tags = {"Stock Movements"})
    @ApiResponse(responseCode = "201", description = "Movement recorded")
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "422", description = "Insufficient stock")
    public ResponseEntity<Void> recordMovement(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The directional movement to post: SKU, source location, movement type"
                                    + " and quantity, plus a destination for transfers.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Receive twelve units", value = """
                                                                    {"productSku":"SKU-10042",
                                                                     "fromLocationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
                                                                     "movementType":"RECEIVE",
                                                                     "quantity":12,
                                                                     "unitOfMeasure":"EACH",
                                                                     "sourceTransactionId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RecordMovementRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        log.info(
                "POST /v1/inventory/stock-movements movementType={} productSku={} actor={}",
                request.getMovementType(),
                request.getProductSku(),
                actorUserId);
        stockMovementService.recordMovement(request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/v1/inventory/adjustments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:create"})
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @EmitEvent(id = "INVENTORY_ADJUSTMENT_REQUEST_CREATE", apiVersion = "1")
    @Operation(
            operationId = "createAdjustmentRequest",
            summary = "Create adjustment request",
            description = """
                    Creates a PENDING inventory adjustment request that must be approved before any stock change \
                    posts.
                    Use this tool to propose a quantity correction, then post it with approveAdjustmentRequest; do \
                    not use createStockMovement, whose ADJUST type is rejected — this two-step workflow is the \
                    only correction path.
                    Preconditions: none are checked at creation; the SKU, location and quantity are validated for \
                    shape only, not against on-hand.
                    Required inputs: productSku, locationId (UUID), quantity (integer — positive adds stock, \
                    negative removes it) and reasonCode; unitOfMeasure is optional.
                    Emits an INVENTORY_ADJUSTMENT_REQUEST_CREATE event; no ledger entry is written and \
                    availability is unchanged until approval.
                    Returns 201 with the PENDING request, and 400 when productSku, locationId, quantity or \
                    reasonCode is missing.
                    """,
            tags = {"Stock Movements"})
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Adjustment request created",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = AdjustmentRequestResponse.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Validation failure",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "User lacks required create permission",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class)))
            })
    public ResponseEntity<AdjustmentRequestResponse> createAdjustmentRequest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The proposed correction: SKU, location, signed quantity and the reason"
                                    + " code justifying it.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Cycle-count write-off", value = """
                                                                    {"productSku":"SKU-10042",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
                                                                     "quantity":-3,
                                                                     "reasonCode":"CYCLE_COUNT",
                                                                     "unitOfMeasure":"EACH"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateAdjustmentRequestDto request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        log.info("POST /v1/inventory/adjustments productSku={} actor={}", request.getProductSku(), actorUserId);

        AdjustmentRequestResponse response = stockMovementService.createAdjustmentRequest(request, actorUserId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/v1/inventory/adjustments/{adjustmentRequestId}/approve")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:approve"})
    @PreAuthorize("hasAuthority('inventory:adjustment:approve')")
    @EmitEvent(id = "INVENTORY_ADJUSTMENT_REQUEST_APPROVE", apiVersion = "1")
    @Operation(
            operationId = "approveAdjustmentRequest",
            summary = "Approve adjustment request",
            description = """
                    Approves a PENDING adjustment request and posts the resulting ADJUSTMENT_IN or ADJUSTMENT_OUT \
                    entry to the inventory ledger.
                    Use this tool as the second step of the adjustment workflow, after createAdjustmentRequest; do \
                    not use createStockMovement to post corrections, and note approval needs the separate \
                    inventory:adjustment:approve authority.
                    Preconditions: the adjustment request must exist and still be PENDING.
                    Required inputs: adjustmentRequestId (UUID) path parameter; there is no request body.
                    Emits an INVENTORY_ADJUSTMENT_REQUEST_APPROVE event; the request is stamped APPROVED with \
                    approver and timestamp, and the ledger posting updates the stock summary that availability \
                    reads.
                    Returns 400 when the adjustment request id is unknown (the lookup failure maps to a validation \
                    error rather than 404), and 409 when the request is no longer PENDING.
                    """,
            tags = {"Stock Movements"})
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Adjustment approved"),
                @ApiResponse(
                        responseCode = "400",
                        description = "Validation failure",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "User lacks required approval permission",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Referenced resource not found",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "409",
                        description = "Adjustment request is in a conflicting state",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "422",
                        description = "Business rule validation failed",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class)))
            })
    public ResponseEntity<Void> approveAdjustmentRequest(@PathVariable UUID adjustmentRequestId) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        log.info("POST /v1/inventory/adjustments/{}/approve actor={}", adjustmentRequestId, actorUserId);
        stockMovementService.approveAdjustmentRequest(adjustmentRequestId, actorUserId);
        return ResponseEntity.ok().build();
    }
}

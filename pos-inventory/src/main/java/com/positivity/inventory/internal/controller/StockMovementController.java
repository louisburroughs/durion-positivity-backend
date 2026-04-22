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
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
@Tag(name = "Stock Movements", description = "Inventory ledger movement recording and adjustment endpoints")
public class StockMovementController {

        private static final String NO_CURRENT_USER = "No current user";
        private final StockMovementService stockMovementService;

        public StockMovementController(StockMovementService stockMovementService) {
                this.stockMovementService = stockMovementService;
        }

        @PostMapping("/v1/inventory/stock-movements")
        @PreAuthorize("hasAuthority('inventory:stock_movement:create')")
        @EmitEvent(id = "INVENTORY_STOCK_MOVEMENT_CREATE", apiVersion = "1")
        @Operation(summary = "Record a stock movement", description = "Records a RECEIVE, PUT_AWAY, PICK, ISSUE, RETURN, or TRANSFER movement in the inventory ledger.")
        @ApiResponse(responseCode = "201", description = "Movement recorded")
        @ApiResponse(responseCode = "400", description = "Validation failure")
        @ApiResponse(responseCode = "422", description = "Insufficient stock")
        public ResponseEntity<Void> recordMovement(@Valid @RequestBody RecordMovementRequest request) {
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
        @PreAuthorize("hasAuthority('inventory:adjustment:create')")
        @EmitEvent(id = "INVENTORY_ADJUSTMENT_REQUEST_CREATE", apiVersion = "1")
        @Operation(summary = "Create adjustment request", description = "Creates a pending adjustment request for approval before posting to the inventory ledger.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "201", description = "Adjustment request created", content = @Content(mediaType = "application/json", schema = @Schema(implementation = AdjustmentRequestResponse.class))),
                        @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                        @ApiResponse(responseCode = "403", description = "User lacks required create permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        })
        public ResponseEntity<AdjustmentRequestResponse> createAdjustmentRequest(
                        @Valid @RequestBody CreateAdjustmentRequestDto request) {
                String actorUserId = SecurityContextHelper.getCurrentUsername()
                                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
                log.info("POST /v1/inventory/adjustments productSku={} actor={}", request.getProductSku(), actorUserId);

                AdjustmentRequestResponse response = stockMovementService.createAdjustmentRequest(request, actorUserId);

                return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }

        @PostMapping("/v1/inventory/adjustments/{adjustmentRequestId}/approve")
        @PreAuthorize("hasAuthority('inventory:adjustment:approve')")
        @EmitEvent(id = "INVENTORY_ADJUSTMENT_REQUEST_APPROVE", apiVersion = "1")
        @Operation(summary = "Approve adjustment request", description = "Approves a pending adjustment request and posts the resulting movement to the inventory ledger.")
        @ApiResponses(value = {
                        @ApiResponse(responseCode = "200", description = "Adjustment approved"),
                        @ApiResponse(responseCode = "400", description = "Validation failure", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                        @ApiResponse(responseCode = "403", description = "User lacks required approval permission", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                        @ApiResponse(responseCode = "404", description = "Referenced resource not found", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                        @ApiResponse(responseCode = "409", description = "Adjustment request is in a conflicting state", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class))),
                        @ApiResponse(responseCode = "422", description = "Business rule validation failed", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
        })
        public ResponseEntity<Void> approveAdjustmentRequest(@PathVariable UUID adjustmentRequestId) {
                String actorUserId = SecurityContextHelper.getCurrentUsername()
                                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
                log.info("POST /v1/inventory/adjustments/{}/approve actor={}", adjustmentRequestId, actorUserId);
                stockMovementService.approveAdjustmentRequest(adjustmentRequestId, actorUserId);
                return ResponseEntity.ok().build();
        }
}

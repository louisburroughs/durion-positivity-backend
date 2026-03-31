package com.positivity.inventory.internal.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.service.CycleCountAdjustmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * REST controller for cycle count adjustment operations.
 * 
 * <p>
 * Provides endpoints for:
 * <ul>
 * <li>Creating adjustments from cycle counts</li>
 * <li>Approving or rejecting adjustments</li>
 * <li>Querying adjustment status and history</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/inventory/cycleCountAdjustments")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Cycle Count Adjustments", description = "Manage inventory adjustments from cycle counts")
public class CycleCountAdjustmentController {

    private final CycleCountAdjustmentService adjustmentService;

    /**
     * Creates a new cycle count adjustment.
     * 
     * <p>
     * The adjustment will be evaluated against approval thresholds:
     * <ul>
     * <li>If below all thresholds: auto-approved and posted immediately</li>
     * <li>If exceeds any threshold: enters PENDING_APPROVAL status</li>
     * </ul>
     * 
     * @param request the adjustment creation request
     * @return the created adjustment with its assigned status
     */
    @PostMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_ADJUSTMENT_CREATE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:create')")
    @Operation(summary = "Create cycle count adjustment", description = "Creates a new adjustment from a cycle count. Automatically evaluates against approval thresholds.")
    @ApiResponse(responseCode = "201", description = "Adjustment created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request or no variance detected")
    public ResponseEntity<AdjustmentResponse> createAdjustment(
            @Valid @RequestBody CreateAdjustmentRequest request) {
        log.info("Received request to create adjustment for SKU {}", request.getStockItemId());
        AdjustmentResponse response = adjustmentService.createAdjustment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Approves a pending adjustment.
     * 
     * <p>
     * Requires the user to have INVENTORY_ADJUSTMENT_APPROVE permission.
     * Upon approval, the adjustment is posted to the inventory ledger.
     * 
     * @param adjustmentId the adjustment ID
     * @param request      the approval request with approver details
     * @return the approved and posted adjustment
     */
    @PostMapping("/{adjustmentId}/approve")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_ADJUSTMENT_APPROVE", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:approve')")
    @Operation(summary = "Approve adjustment", description = "Approves a pending adjustment and posts it to the inventory ledger")
    @ApiResponse(responseCode = "200", description = "Adjustment approved and posted")
    @ApiResponse(responseCode = "400", description = "Adjustment not found or not in approvable state")
    @ApiResponse(responseCode = "403", description = "User lacks required approval permission")
    public ResponseEntity<AdjustmentResponse> approveAdjustment(
            @Parameter(description = "Adjustment ID", required = true) @PathVariable UUID adjustmentId,
            @Valid @RequestBody ApproveAdjustmentRequest request,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        log.info("Received request to approve adjustment {}", adjustmentId);
        AdjustmentResponse response = adjustmentService.approveAdjustment(adjustmentId, request, correlationId);
        return ResponseEntity.ok(response);
    }

    /**
     * Rejects a pending adjustment.
     * 
     * <p>
     * Requires the user to have INVENTORY_ADJUSTMENT_APPROVE permission.
     * Rejection is final - no changes are made to inventory.
     * 
     * 
     * @param adjustmentId the adjustment ID
     * @param request      the rejection request with reason
     * @return the rejected adjustment
     */
    @PostMapping("/{adjustmentId}/reject")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_ADJUSTMENT_REJECT", apiVersion = "1")
    @PreAuthorize("hasAuthority('inventory:adjustment:approve')")
    @Operation(summary = "Reject adjustment", description = "Rejects a pending adjustment with a reason. No inventory changes are made.")
    @ApiResponse(responseCode = "200", description = "Adjustment rejected")
    @ApiResponse(responseCode = "400", description = "Adjustment not found or not in rejectable state")
    @ApiResponse(responseCode = "403", description = "User lacks required approval permission")
    public ResponseEntity<AdjustmentResponse> rejectAdjustment(
            @Parameter(description = "Adjustment ID", required = true) @PathVariable UUID adjustmentId,
            @Valid @RequestBody RejectAdjustmentRequest request) {
        log.info("Received request to reject adjustment {}", adjustmentId);
        AdjustmentResponse response = adjustmentService.rejectAdjustment(adjustmentId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a specific adjustment by ID.
     * 
     * @param adjustmentId the adjustment ID
     * @return the adjustment details
     */
    @GetMapping("/{adjustmentId}")
    @PreAuthorize("hasAnyAuthority('inventory:adjustment:view','inventory:adjustment:approve')")
    @Operation(summary = "Get adjustment details", description = "Retrieves details of a specific cycle count adjustment")
    @ApiResponse(responseCode = "200", description = "Adjustment found")
    @ApiResponse(responseCode = "404", description = "Adjustment not found")
    public ResponseEntity<AdjustmentResponse> getAdjustment(
            @Parameter(description = "Adjustment ID", required = true) @PathVariable UUID adjustmentId) {
        AdjustmentResponse response = adjustmentService.getAdjustment(adjustmentId);
        return ResponseEntity.ok(response);
    }

    /**
     * Lists all adjustments with a specific status.
     * 
     * @param status the adjustment status filter
     * @return list of matching adjustments
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('inventory:adjustment:view','inventory:adjustment:approve')")
    @Operation(summary = "List adjustments by status", description = "Lists all adjustments matching the specified status")
    @ApiResponse(responseCode = "200", description = "Adjustments retrieved")
    public ResponseEntity<List<AdjustmentResponse>> listAdjustments(
            @Parameter(description = "Filter by adjustment status") @RequestParam(required = false) AdjustmentStatus status) {
        List<AdjustmentResponse> response = status != null
                ? adjustmentService.listAdjustmentsByStatus(status)
                : adjustmentService.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);
        return ResponseEntity.ok(response);
    }

    /**
     * Lists all pending approvals.
     * 
     * @return list of adjustments awaiting approval
     */
    @GetMapping("/pending")
    @PreAuthorize("hasAnyAuthority('inventory:adjustment:view','inventory:adjustment:approve')")
    @Operation(summary = "List pending approvals", description = "Lists all adjustments awaiting approval")
    @ApiResponse(responseCode = "200", description = "Pending adjustments retrieved")
    public ResponseEntity<List<AdjustmentResponse>> listPendingApprovals() {
        List<AdjustmentResponse> response = adjustmentService
                .listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);
        return ResponseEntity.ok(response);
    }

    /**
     * Gets the count of pending approvals.
     * 
     * @return count of adjustments awaiting approval
     */
    @GetMapping("/pending/count")
    @PreAuthorize("hasAnyAuthority('inventory:adjustment:view','inventory:adjustment:approve')")
    @Operation(summary = "Count pending approvals", description = "Returns the count of adjustments awaiting approval")
    @ApiResponse(responseCode = "200", description = "Count retrieved")
    public ResponseEntity<Long> countPendingApprovals() {
        long count = adjustmentService.countAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);
        return ResponseEntity.ok(count);
    }
}

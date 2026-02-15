package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.*;
import com.positivity.workorder.service.WorkorderPartAdjustmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * REST controller for workorder part adjustments (substitutions, returns,
 * corrections).
 * 
 * CAP:005 Story #157 - Handle Part Substitutions and Returns
 * 
 * Provides endpoints to:
 * - Substitute parts (preserving original for audit history)
 * - Return unused part quantities
 * - Correct part quantities (administrative corrections)
 * - Query adjustment history
 */
@RestController
@RequestMapping("/v1/workorders/{workorderId}/parts")
@Tag(name = "Workorder Part Adjustments", description = "Part substitutions, returns, and corrections")
public class WorkorderPartAdjustmentController {

    private final WorkorderPartAdjustmentService adjustmentService;

    public WorkorderPartAdjustmentController(WorkorderPartAdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
    }

    /**
     * Substitute one part for another.
     */
    @PostMapping("/substitute")
    @PreAuthorize("hasAuthority('workorder:parts:add')")
    @EmitEvent(id = "WORKORDER_PART_SUBSTITUTE", apiVersion = "1")
    @Operation(summary = "Substitute part", description = "Replace one part with another. Original part preserved for history.")
    @ApiResponse(responseCode = "201", description = "Part substituted successfully", content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (part already consumed, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    @ApiResponse(responseCode = "409", description = "Idempotency conflict")
    public ResponseEntity<WorkorderPartAdjustmentEventResponse> substitutePart(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull SubstitutePartRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = true) @NonNull UUID userId) {

        try {
            WorkorderPartAdjustmentEventResponse response = adjustmentService.substitutePartLine(
                    workorderId,
                    request.getOriginalPartId(),
                    request.getSubstitutePartId(),
                    request.getReason(),
                    userId,
                    idempotencyKey,
                    request.getNotes());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Return unused part quantity.
     */
    @PostMapping("/returnUnused")
    @PreAuthorize("hasAuthority('workorder:parts:add')")
    @EmitEvent(id = "WORKORDER_PART_RETURN_UNUSED", apiVersion = "1")
    @Operation(summary = "Return unused quantity", description = "Return unused part quantity beyond normal return flow")
    @ApiResponse(responseCode = "201", description = "Unused quantity returned successfully", content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request (exceeds available quantity, etc.)")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    public ResponseEntity<WorkorderPartAdjustmentEventResponse> returnUnusedQuantity(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull ReturnPartQuantityRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = true) @NonNull UUID userId) {

        try {
            WorkorderPartAdjustmentEventResponse response = adjustmentService.returnUnusedQuantity(
                    workorderId,
                    request.getWorkorderPartId(),
                    request.getQuantity(),
                    request.getReason(),
                    userId,
                    idempotencyKey,
                    request.getNotes());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Correct part quantity (administrative correction).
     */
    @PostMapping("/correct")
    @PreAuthorize("hasAuthority('workorder:parts:add')")
    @EmitEvent(id = "WORKORDER_PART_CORRECT", apiVersion = "1")
    @Operation(summary = "Correct part quantity", description = "Administrative correction for data entry errors")
    @ApiResponse(responseCode = "201", description = "Part quantity corrected successfully", content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    public ResponseEntity<WorkorderPartAdjustmentEventResponse> correctPartQuantity(
            @PathVariable @NonNull UUID workorderId,
            @RequestBody @Valid @NonNull CorrectPartQuantityRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) @Nullable String idempotencyKey,
            @RequestHeader(value = "X-User-Id", required = true) @NonNull UUID userId) {

        try {
            WorkorderPartAdjustmentEventResponse response = adjustmentService.correctPartQuantity(
                    workorderId,
                    request.getWorkorderPartId(),
                    request.getNewQuantity(),
                    request.getReason(),
                    userId,
                    idempotencyKey,
                    request.getNotes());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (NoSuchElementException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get adjustment history for all parts on a workorder, or a specific part if
     * partId is provided.
     */
    @GetMapping("/adjustments")
    @PreAuthorize("hasAuthority('workorder:parts:view')")
    @Operation(summary = "Get part adjustment history", description = "Retrieve adjustment history (substitutions, returns, corrections) for parts on the workorder")
    @ApiResponse(responseCode = "200", description = "Adjustment history retrieved successfully (newest first)", content = @Content(schema = @Schema(implementation = WorkorderPartAdjustmentEventResponse.class)))
    @ApiResponse(responseCode = "404", description = "Workorder or part not found")
    public ResponseEntity<List<WorkorderPartAdjustmentEventResponse>> getAdjustmentHistory(
            @PathVariable @NonNull UUID workorderId,
            @RequestParam(required = false) @Nullable @Parameter(description = "Optional part ID to filter history for a specific part") UUID partId) {

        List<WorkorderPartAdjustmentEventResponse> responses;
        if (partId != null) {
            responses = adjustmentService.getPartAdjustmentHistory(partId);
        } else {
            responses = adjustmentService.getAdjustmentHistory(workorderId);
        }

        return ResponseEntity.ok(responses);
    }
}

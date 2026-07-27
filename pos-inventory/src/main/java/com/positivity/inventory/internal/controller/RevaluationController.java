package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.revaluation.CreateRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RejectRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RevaluationResponse;
import com.positivity.inventory.internal.enums.RevaluationStatus;
import com.positivity.inventory.service.RevaluationService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the manual cost-revaluation workflow (odoo-parity J4, issue #1054).
 *
 * <p>A revaluation corrects a SKU's standard price (STANDARD) or running average (AVERAGE). The
 * inventory value delta gates the approval tier: below-threshold revaluations auto-apply (cost state
 * restated, {@code ProductValueChangedV1} emitted) in the create transaction; above-threshold ones
 * require a separate approval before applying. Mutations require {@code inventory:valuation:adjust};
 * reads are visible to {@code inventory:valuation:view} or {@code inventory:valuation:adjust}.
 */
@RestController
@RequestMapping("/v1/inventory/valuation/revaluations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Inventory Revaluation", description = "Approval-gated manual cost revaluation (odoo-parity J4)")
public class RevaluationController {

    private final RevaluationService revaluationService;

    /**
     * Submits a revaluation; below-threshold revaluations auto-apply and restate the cost state.
     *
     * @param request the revaluation request
     * @return the created revaluation with its resulting status
     */
    @PostMapping
    @EmitEvent(id = "INVENTORY_REVALUATION_CREATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:valuation:adjust"})
    @PreAuthorize("hasAuthority('inventory:valuation:adjust')")
    @Operation(
            summary = "Submit cost revaluation",
            description = "Submits a manual cost revaluation for a SKU. Below the configured value-delta thresholds"
                    + " the revaluation is auto-applied (cost state restated and ProductValueChangedV1 emitted) in"
                    + " the same transaction; at or above them it enters PENDING_APPROVAL with no cost change.",
            tags = {"Inventory Revaluation"})
    @ApiResponse(responseCode = "201", description = "Revaluation created (AUTO_APPLIED or PENDING_APPROVAL)")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request (e.g. neither/both of newUnitCost and costDelta, or a negative result)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:valuation:adjust",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RevaluationResponse> createRevaluation(@Valid @RequestBody CreateRevaluationRequest request) {
        log.info("Received request to revalue {}", request.getStockItemId());
        return ResponseEntity.status(HttpStatus.CREATED).body(revaluationService.createRevaluation(request));
    }

    /**
     * Approves a pending revaluation and applies it to the SKU cost state.
     *
     * @param revaluationId the revaluation document id
     * @return the applied revaluation
     */
    @PostMapping("/{revaluationId}/approve")
    @EmitEvent(id = "INVENTORY_REVALUATION_APPROVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:valuation:adjust"})
    @PreAuthorize("hasAuthority('inventory:valuation:adjust')")
    @Operation(
            summary = "Approve cost revaluation",
            description = "Approves a pending revaluation, restating the SKU cost state and emitting"
                    + " ProductValueChangedV1",
            tags = {"Inventory Revaluation"})
    @ApiResponse(responseCode = "200", description = "Revaluation approved and applied")
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:valuation:adjust",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Revaluation not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Revaluation is not in an approvable state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RevaluationResponse> approveRevaluation(
            @Parameter(description = "Revaluation document ID", required = true) @PathVariable UUID revaluationId) {
        log.info("Received request to approve revaluation {}", revaluationId);
        return ResponseEntity.ok(revaluationService.approveRevaluation(revaluationId));
    }

    /**
     * Rejects a pending revaluation; the SKU cost state is untouched.
     *
     * @param revaluationId the revaluation document id
     * @param request the rejection request with reason
     * @return the rejected revaluation
     */
    @PostMapping("/{revaluationId}/reject")
    @EmitEvent(id = "INVENTORY_REVALUATION_REJECT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:valuation:adjust"})
    @PreAuthorize("hasAuthority('inventory:valuation:adjust')")
    @Operation(
            summary = "Reject cost revaluation",
            description = "Rejects a pending revaluation with a reason. The SKU cost state is untouched.",
            tags = {"Inventory Revaluation"})
    @ApiResponse(responseCode = "200", description = "Revaluation rejected")
    @ApiResponse(
            responseCode = "404",
            description = "Revaluation not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Revaluation is not in a rejectable state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RevaluationResponse> rejectRevaluation(
            @Parameter(description = "Revaluation document ID", required = true) @PathVariable UUID revaluationId,
            @Valid @RequestBody RejectRevaluationRequest request) {
        log.info("Received request to reject revaluation {}", revaluationId);
        return ResponseEntity.ok(revaluationService.rejectRevaluation(revaluationId, request));
    }

    /**
     * Retrieves one revaluation document.
     *
     * @param revaluationId the revaluation document id
     * @return the revaluation
     */
    @GetMapping("/{revaluationId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:valuation:view", "inventory:valuation:adjust"})
    @PreAuthorize("hasAnyAuthority('inventory:valuation:view','inventory:valuation:adjust')")
    @Operation(
            summary = "Get revaluation details",
            description = "Retrieves one revaluation document",
            tags = {"Inventory Revaluation"})
    @ApiResponse(responseCode = "200", description = "Revaluation found")
    @ApiResponse(
            responseCode = "404",
            description = "Revaluation not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RevaluationResponse> getRevaluation(
            @Parameter(description = "Revaluation document ID", required = true) @PathVariable UUID revaluationId) {
        return ResponseEntity.ok(revaluationService.getRevaluation(revaluationId));
    }

    /**
     * Lists revaluation documents matching the optional filters, newest first.
     *
     * @param stockItemId optional SKU filter
     * @param status optional status filter
     * @return matching revaluations
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:valuation:view", "inventory:valuation:adjust"})
    @PreAuthorize("hasAnyAuthority('inventory:valuation:view','inventory:valuation:adjust')")
    @Operation(
            summary = "List revaluations",
            description = "Lists revaluation documents filtered by SKU and lifecycle status, newest first",
            tags = {"Inventory Revaluation"})
    @ApiResponse(
            responseCode = "200",
            description = "Revaluations retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = RevaluationResponse.class))))
    public ResponseEntity<List<RevaluationResponse>> listRevaluations(
            @Parameter(description = "Filter by SKU / stock item identifier") @RequestParam(required = false)
                    String stockItemId,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false)
                    RevaluationStatus status) {
        return ResponseEntity.ok(revaluationService.listRevaluations(stockItemId, status));
    }
}

package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.costing.service.RevaluationService;
import com.positivity.inventory.internal.dto.revaluation.CreateRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RejectRevaluationRequest;
import com.positivity.inventory.internal.dto.revaluation.RevaluationResponse;
import com.positivity.inventory.internal.enums.RevaluationStatus;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.VALUATION_ADJUST + "')")
    @Operation(
            operationId = "createRevaluation",
            summary = "Submit cost revaluation",
            description = """
                    Submits a manual cost revaluation for a SKU, correcting the cost its resolved costing method \
                    uses — the standard price under STANDARD, the running average under AVERAGE; below the \
                    configured value-delta thresholds it is AUTO_APPLIED (cost state restated and a \
                    ProductValueChangedV1 fact emitted) in the same transaction, and at or above them it enters \
                    PENDING_APPROVAL with no cost change.
                    Use this tool to correct a wrong unit cost; do not use upsertCostingMethodConfig, which \
                    switches the costing method without restating values, and do not use approveRevaluation, \
                    which acts on a revaluation that already exists.
                    Preconditions: the caller must be an authenticated user; the SKU's cost state row is seeded \
                    automatically when absent, so no prior costing history is required.
                    Required inputs: stockItemId plus exactly one of newUnitCost (absolute, zero or positive) or \
                    costDelta (signed, applied to the current cost), and a reason for the audit log; the value \
                    delta gating the approval tier is the cost change multiplied by current on-hand.
                    Emits an INVENTORY_REVALUATION_CREATE event, and the auto-apply path additionally emits the \
                    ProductValueChangedV1 fact so accounting can post the revaluation journal entry.
                    Returns 400 when neither or both of newUnitCost and costDelta are supplied, or when the \
                    resulting unit cost would be negative.
                    """,
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
    public ResponseEntity<RevaluationResponse> createRevaluation(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cost correction for one SKU: an absolute new unit cost or a signed"
                                    + " delta, with the justification recorded in the audit log.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Absolute correction", value = """
                                                                    {"stockItemId":"OIL-5W30-5QT",
                                                                     "newUnitCost":5.5000,
                                                                     "reason":"Q3 physical recount valuation correction"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateRevaluationRequest request) {
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.VALUATION_ADJUST + "')")
    @Operation(
            operationId = "approveRevaluation",
            summary = "Approve cost revaluation",
            description = """
                    Approves a PENDING_APPROVAL revaluation and applies it: the SKU's cost state is restated to \
                    the recorded new unit cost and a ProductValueChangedV1 fact is emitted for the accounting \
                    journal entry.
                    Use this tool after reviewing an above-threshold revaluation; do not use rejectRevaluation, \
                    which discards it and leaves the cost state untouched.
                    Preconditions: the revaluation must exist and be in PENDING_APPROVAL status; the approver need \
                    not differ from the submitter.
                    Required inputs: revaluationId (UUID) as a path parameter; there is no request body.
                    Emits an INVENTORY_REVALUATION_APPROVE event plus the ProductValueChangedV1 fact; the new cost \
                    applies from now on and no ledger entries are rewritten.
                    Returns 404 when the revaluation does not exist, and 409 when it is not in PENDING_APPROVAL \
                    status.
                    """,
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
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.VALUATION_ADJUST + "')")
    @Operation(
            operationId = "rejectRevaluation",
            summary = "Reject cost revaluation",
            description = """
                    Rejects a PENDING_APPROVAL revaluation with a recorded reason; the SKU cost state is untouched \
                    and the record moves to the terminal REJECTED status.
                    Use this tool to discard a disputed cost correction; do not use approveRevaluation, which \
                    restates the SKU cost state.
                    Preconditions: the revaluation must exist and be in PENDING_APPROVAL status; the rejecting \
                    actor is taken from the authenticated context, not the body.
                    Required inputs: revaluationId (UUID) path parameter and rejectionReason (non-blank, max 1000 \
                    characters) in the body.
                    Emits an INVENTORY_REVALUATION_REJECT event; no cost or ledger change is made.
                    Returns 404 when the revaluation does not exist, and 409 when it is not in PENDING_APPROVAL \
                    status.
                    """,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rejection context recording why the cost correction is discarded.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rejection", value = """
                                                                    {"rejectionReason":"Recount disputed; leave standard price unchanged"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RejectRevaluationRequest request) {
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
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.VALUATION_VIEW + "','"
            + InventoryPermissionRegistry.VALUATION_ADJUST + "')")
    @Operation(
            operationId = "getRevaluation",
            summary = "Get revaluation details",
            description = """
                    Returns one revaluation document with its old and new unit cost, on-hand snapshot, signed \
                    value delta, approval tier and lifecycle status.
                    Use this tool when the revaluationId is already known; use listRevaluations instead to search \
                    by SKU or status.
                    Preconditions: the revaluation must exist.
                    Required inputs: revaluationId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no revaluation exists for the supplied id.
                    """,
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
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.VALUATION_VIEW + "','"
            + InventoryPermissionRegistry.VALUATION_ADJUST + "')")
    @Operation(
            operationId = "listRevaluations",
            summary = "List revaluations",
            description = """
                    Returns revaluation documents, newest first, optionally filtered by SKU and/or lifecycle \
                    status (PENDING_APPROVAL, AUTO_APPLIED, APPLIED, REJECTED).
                    Use this tool to find pending approvals or audit past cost corrections; use getRevaluation \
                    instead when the revaluationId is already known.
                    Preconditions: none.
                    Required inputs: stockItemId and status are optional query parameters; there is no paging — \
                    the full match set is returned.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when no revaluations match, so an empty result is not an \
                    error condition.
                    """,
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

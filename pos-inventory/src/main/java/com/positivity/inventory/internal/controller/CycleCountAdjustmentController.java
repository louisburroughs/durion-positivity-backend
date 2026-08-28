package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.cyclecount.service.CycleCountAdjustmentService;
import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.dto.cyclecount.ApproveAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.CreateAdjustmentRequest;
import com.positivity.inventory.internal.dto.cyclecount.RejectAdjustmentRequest;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_CREATE + "')")
    @Operation(
            operationId = "createCycleCountAdjustment",
            summary = "Create cycle count adjustment",
            description = """
                    Creates an inventory adjustment from a cycle count variance and evaluates it against the \
                    configured approval thresholds: below all thresholds it is AUTO_APPROVED and its \
                    COUNT_VARIANCE_IN or COUNT_VARIANCE_OUT entry posts to the inventory ledger in the same \
                    transaction, otherwise it enters PENDING_APPROVAL with a required approval tier.
                    Use this tool to settle a counted variance; do not use submitCycleCount, which only records a \
                    count, and do not use approveCycleCountAdjustment, which acts on an adjustment that already \
                    exists.
                    Preconditions: countedQuantity must differ from quantityOnHandBefore (a zero variance is \
                    rejected), a supplied taskId must reference an existing cycle count task, and the auto-approve \
                    path additionally requires the linked task to have no unreviewed in-window stock movements.
                    Required inputs: stockItemId (the ledger's freeform stock reference text — a SKU code, or a \
                    product UUID rendered as text; not a product id), reasonCode, countedQuantity, quantityOnHandBefore, \
                    costAtTimeOfAdjustment and createdByUserId; taskId is optional but enables conflict detection; \
                    the posted unit cost prefers the costing engine's per-SKU running cost and falls back to \
                    costAtTimeOfAdjustment only for a SKU the engine has not costed.
                    Emits an INVENTORY_CYCLE_COUNT_ADJUSTMENT_CREATE event, and an auto-approved posting changes \
                    on-hand immediately.
                    Returns 400 when the counted quantity matches the system quantity, 404 when the referenced \
                    task does not exist, and 409 (CYCLE_COUNT_CONFLICT) when auto-approval detects in-window \
                    movements — the task is flagged CONFLICT and a reviewer must explicitly recount or approve \
                    with recomputation.
                    """,
            tags = {"Cycle Count Adjustments"})
    @ApiResponse(responseCode = "201", description = "Adjustment created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request or no variance detected")
    @ApiResponse(
            responseCode = "404",
            description = "Referenced cycle count task not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "In-window stock movements detected on auto-approval (CYCLE_COUNT_CONFLICT)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AdjustmentResponse> createAdjustment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cycle count variance to turn into an inventory adjustment, with the"
                                    + " before/after quantities and the fallback unit cost.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Task-linked shortage", value = """
                                                                    {"stockItemId":"OIL-5W30-5QT",
                                                                     "taskId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "reasonCode":"CYCLE_COUNT_VARIANCE",
                                                                     "countedQuantity":12,
                                                                     "quantityOnHandBefore":15,
                                                                     "costAtTimeOfAdjustment":12.50,
                                                                     "createdByUserId":"user-10042"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateAdjustmentRequest request) {
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:approve"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "approveCycleCountAdjustment",
            summary = "Approve adjustment",
            description = """
                    Approves a PENDING_APPROVAL cycle count adjustment and posts its COUNT_VARIANCE_IN or \
                    COUNT_VARIANCE_OUT entry to the inventory ledger, marking the linked task APPROVED.
                    Use this tool after reviewing a pending adjustment; do not use rejectCycleCountAdjustment, \
                    which discards it without touching inventory.
                    Preconditions: the adjustment must be in PENDING_APPROVAL; for a task-linked adjustment the \
                    conflict gate applies — a first approval that detects in-window stock movements flags the task \
                    CONFLICT and aborts, and re-approving a CONFLICT task recomputes the variance against current \
                    on-hand rather than the stale snapshot (a recomputed zero variance approves without posting \
                    anything).
                    Required inputs: adjustmentId (UUID) path parameter; the body's approverUserId is legacy and \
                    ignored — the actor is resolved from the authenticated context; notes are optional, and an \
                    optional X-Correlation-Id header is propagated to the audit event.
                    Emits an INVENTORY_CYCLE_COUNT_ADJUSTMENT_APPROVE event plus a MovementAdjusted audit event, \
                    and the posting changes on-hand immediately.
                    Returns 400 when no adjustment exists for the id (the unknown id maps to a validation error, \
                    not 404), and 409 when the adjustment is not PENDING_APPROVAL or the conflict gate rejects the \
                    first approval (CYCLE_COUNT_CONFLICT).
                    """,
            tags = {"Cycle Count Adjustments"})
    @ApiResponse(responseCode = "200", description = "Adjustment approved and posted")
    @ApiResponse(responseCode = "400", description = "Adjustment not found or not in approvable state")
    @ApiResponse(responseCode = "403", description = "User lacks required approval permission")
    @ApiResponse(
            responseCode = "409",
            description = "Adjustment not PENDING_APPROVAL, or conflict gate rejected the approval"
                    + " (CYCLE_COUNT_CONFLICT)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AdjustmentResponse> approveAdjustment(
            @Parameter(description = "Adjustment ID", required = true) @PathVariable UUID adjustmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Approval context; the authoritative approver is resolved server-side"
                                    + " from the authenticated caller.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Approval with notes", value = """
                                                                    {"notes":"Variance confirmed against receiving log"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ApproveAdjustmentRequest request,
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:approve"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "rejectCycleCountAdjustment",
            summary = "Reject adjustment",
            description = """
                    Rejects a PENDING_APPROVAL cycle count adjustment with a recorded reason; rejection is final \
                    and no inventory or ledger change is made.
                    Use this tool to discard a variance that should not post; do not use \
                    approveCycleCountAdjustment, which posts the variance to the ledger.
                    Preconditions: the adjustment must be in PENDING_APPROVAL status.
                    Required inputs: adjustmentId (UUID) path parameter plus rejectorUserId and rejectionReason in \
                    the body, both non-blank.
                    Emits an INVENTORY_CYCLE_COUNT_ADJUSTMENT_REJECT event; the adjustment moves to REJECTED and \
                    its linked task is left untouched.
                    Returns 400 when no adjustment exists for the id (mapped to a validation error, not 404), and \
                    409 when the adjustment is not PENDING_APPROVAL.
                    """,
            tags = {"Cycle Count Adjustments"})
    @ApiResponse(responseCode = "200", description = "Adjustment rejected")
    @ApiResponse(responseCode = "400", description = "Adjustment not found or not in rejectable state")
    @ApiResponse(responseCode = "403", description = "User lacks required approval permission")
    @ApiResponse(
            responseCode = "409",
            description = "Adjustment is not in PENDING_APPROVAL status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<AdjustmentResponse> rejectAdjustment(
            @Parameter(description = "Adjustment ID", required = true) @PathVariable UUID adjustmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Rejection context recording who rejected the adjustment and why.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rejection", value = """
                                                                    {"rejectorUserId":"user-30077",
                                                                     "rejectionReason":"Recount required before posting"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    RejectAdjustmentRequest request) {
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:view", "inventory:adjustment:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_VIEW + "','"
            + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "getCycleCountAdjustment",
            summary = "Get adjustment details",
            description = """
                    Returns one cycle count adjustment with its variance, cost snapshot, approval tier, lifecycle \
                    status and ledger-entry linkage.
                    Use this tool when the adjustmentId is already known; use listCycleCountAdjustments instead to \
                    search by status.
                    Preconditions: the adjustment must exist.
                    Required inputs: adjustmentId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when no adjustment exists for the supplied id — the module maps the unknown-id \
                    lookup to a validation error rather than 404.
                    """,
            tags = {"Cycle Count Adjustments"})
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:view", "inventory:adjustment:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_VIEW + "','"
            + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "listCycleCountAdjustments",
            summary = "List adjustments by status",
            description = """
                    Returns every cycle count adjustment in one lifecycle status.
                    Use this tool to browse adjustments by status; do not use listPendingCycleCountAdjustments, \
                    which returns only the pending-approval subset without a parameter, or \
                    countPendingCycleCountAdjustments, which returns just their number.
                    Preconditions: none.
                    Required inputs: status is an optional query parameter (PENDING_APPROVAL, AUTO_APPROVED, \
                    APPROVED, POSTED, REJECTED or FAILED) and defaults to PENDING_APPROVAL when omitted; there is \
                    no paging.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when no adjustments match, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Cycle Count Adjustments"})
    @ApiResponse(responseCode = "200", description = "Adjustments retrieved")
    public ResponseEntity<List<AdjustmentResponse>> listAdjustments(
            @Parameter(description = "Filter by adjustment status") @RequestParam(required = false)
                    AdjustmentStatus status) {
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
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:view", "inventory:adjustment:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_VIEW + "','"
            + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "listPendingCycleCountAdjustments",
            summary = "List pending approvals",
            description = """
                    Returns every cycle count adjustment currently awaiting approval (status PENDING_APPROVAL).
                    Use this tool to build an approval work queue; use countPendingCycleCountAdjustments instead \
                    when only the badge number is needed, and listCycleCountAdjustments to browse other statuses.
                    Preconditions: none.
                    Required inputs: none; there is no request body, paging or filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty array when nothing awaits approval, so an empty result is not an \
                    error condition.
                    """,
            tags = {"Cycle Count Adjustments"})
    @ApiResponse(responseCode = "200", description = "Pending adjustments retrieved")
    public ResponseEntity<List<AdjustmentResponse>> listPendingApprovals() {
        List<AdjustmentResponse> response =
                adjustmentService.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);
        return ResponseEntity.ok(response);
    }

    /**
     * Gets the count of pending approvals.
     *
     * @return count of adjustments awaiting approval
     */
    @GetMapping("/pending/count")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:adjustment:view", "inventory:adjustment:approve"})
    @PreAuthorize("hasAnyAuthority('" + InventoryPermissionRegistry.ADJUSTMENT_VIEW + "','"
            + InventoryPermissionRegistry.ADJUSTMENT_APPROVE + "')")
    @Operation(
            operationId = "countPendingCycleCountAdjustments",
            summary = "Count pending approvals",
            description = """
                    Returns the number of cycle count adjustments awaiting approval, as a bare JSON number.
                    Use this tool for dashboards and badge counts; use listPendingCycleCountAdjustments instead \
                    when the adjustments themselves are needed.
                    Preconditions: none.
                    Required inputs: none; there is no request body or filtering.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with 0 when nothing awaits approval, so zero is not an error condition.
                    """,
            tags = {"Cycle Count Adjustments"})
    @ApiResponse(responseCode = "200", description = "Count retrieved")
    public ResponseEntity<Long> countPendingApprovals() {
        long count = adjustmentService.countAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL);
        return ResponseEntity.ok(count);
    }
}

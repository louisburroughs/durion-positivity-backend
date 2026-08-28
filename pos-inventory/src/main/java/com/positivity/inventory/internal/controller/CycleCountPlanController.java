package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.cyclecount.service.CycleCountPlanService;
import com.positivity.inventory.internal.dto.cyclecount.plan.CreateCycleCountPlanRequest;
import com.positivity.inventory.internal.dto.cyclecount.plan.CycleCountPlanResponse;
import com.positivity.inventory.internal.dto.cyclecount.plan.UpdateCycleCountPlanStatusRequest;
import com.positivity.inventory.internal.enums.CycleCountPlanStatus;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/inventory/cycleCountPlans")
@RequiredArgsConstructor
@Validated
@Tag(name = "Cycle Count Plans", description = "Cycle count plan management endpoints")
public class CycleCountPlanController {

    private final CycleCountPlanService cycleCountPlanService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:initiate"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_INITIATE + "')")
    @Operation(
            operationId = "createCycleCountPlan",
            summary = "Create cycle count plan",
            description = """
                    Creates a cycle count plan in PLANNED status for one location, covering one or more zones on a \
                    future scheduled date.
                    Use this tool for a one-off, manually initiated count; do not use createCycleCountSchedule, \
                    which sets up a recurring schedule that generates plans automatically.
                    Preconditions: the caller must be an authenticated user (recorded as the plan creator), and \
                    scheduledDate must be strictly after today.
                    Required inputs: locationId (UUID), at least one zone in zoneIds, planName, and scheduledDate \
                    (ISO date, future).
                    Emits an INVENTORY_CYCLE_COUNT_PLAN_CREATE event; no tasks or ledger entries are created by \
                    this call.
                    Returns 400 when locationId is missing, zoneIds is empty, or scheduledDate is not in the \
                    future.
                    """,
            tags = {"Cycle Count Plans"})
    @ApiResponse(
            responseCode = "201",
            description = "Cycle count plan created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountPlanResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    public ResponseEntity<CycleCountPlanResponse> createPlan(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Cycle count plan to create, naming the location, zones, and the future"
                                    + " date the count is scheduled for.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Single-zone plan", value = """
                                                                    {"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "zoneIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"],
                                                                     "planName":"Q1 perishables count",
                                                                     "scheduledDate":"2026-09-15"}
                                                                    """)))
                    @RequestBody
                    CreateCycleCountPlanRequest request) {
        String createdBy = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
        CycleCountPlanResponse response = cycleCountPlanService.createPlan(request, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_VIEW + "')")
    @Operation(
            operationId = "listCycleCountPlans",
            summary = "List cycle count plans",
            description = """
                    Returns one page of cycle count plans, newest first, optionally filtered by location and/or \
                    lifecycle status.
                    Use this tool to discover planIds or review upcoming and completed counts; use \
                    getCycleCountPlan instead when the planId is already known.
                    Preconditions: none.
                    Required inputs: all query parameters are optional — locationId (UUID), status (PLANNED, \
                    STARTED, COMPLETED_PENDING_APPROVAL, APPROVED, REJECTED, CANCELLED), page (0-based, default 0) \
                    and size (default 50).
                    Emits an INVENTORY_CYCLE_COUNT_PLAN_LIST audit event; no plan state changes.
                    Returns 200 with an empty array when no plans match, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Cycle Count Plans"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count plans returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = CycleCountPlanResponse.class))))
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    public ResponseEntity<List<CycleCountPlanResponse>> listPlans(
            @Parameter(description = "Filter by location identifier") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Filter by plan status") @RequestParam(required = false)
                    CycleCountPlanStatus status,
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(cycleCountPlanService.listPlans(locationId, status, page, size));
    }

    @GetMapping("/{planId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_VIEW + "')")
    @Operation(
            operationId = "getCycleCountPlan",
            summary = "Get cycle count plan",
            description = """
                    Returns one cycle count plan with its zones, scheduled date, lifecycle status, and originating \
                    schedule linkage.
                    Use this tool when the planId is already known; use listCycleCountPlans instead to search by \
                    location or status.
                    Preconditions: the plan must exist.
                    Required inputs: planId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no cycle count plan exists for the supplied id.
                    """,
            tags = {"Cycle Count Plans"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count plan returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountPlanResponse.class)))
    @ApiResponse(responseCode = "404", description = "Cycle count plan not found")
    public ResponseEntity<CycleCountPlanResponse> getPlan(
            @Parameter(description = "Cycle count plan identifier", required = true) @PathVariable UUID planId) {
        return ResponseEntity.ok(cycleCountPlanService.getPlan(planId));
    }

    @PutMapping("/{planId}/status")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_PLAN_STATUS_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:complete"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.CYCLE_COUNT_COMPLETE + "')")
    @Operation(
            operationId = "updateCycleCountPlanStatus",
            summary = "Transition cycle count plan status",
            description = """
                    Moves a cycle count plan one step through its lifecycle: PLANNED to STARTED or CANCELLED, \
                    STARTED to COMPLETED_PENDING_APPROVAL or CANCELLED, and COMPLETED_PENDING_APPROVAL to APPROVED \
                    or REJECTED; APPROVED, REJECTED and CANCELLED are terminal.
                    Use this tool for plan lifecycle transitions only; do not use updateCycleCountSchedule, which \
                    edits a recurring schedule's configuration.
                    Preconditions: the plan must exist and the requested status must be a legal transition from \
                    its current status.
                    Required inputs: planId (UUID) path parameter and status (the target lifecycle value) in the \
                    body.
                    Emits an INVENTORY_CYCLE_COUNT_PLAN_STATUS_UPDATE event; approving a schedule-created plan \
                    also restamps the originating schedule's nextDueDate to today plus its frequencyDays, so a \
                    late count does not immediately come due again.
                    Returns 404 when the plan does not exist, and 409 when the transition is not allowed from the \
                    current status.
                    """,
            tags = {"Cycle Count Plans"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count plan status updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountPlanResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    @ApiResponse(responseCode = "404", description = "Cycle count plan not found")
    @ApiResponse(responseCode = "409", description = "Invalid status transition")
    public ResponseEntity<CycleCountPlanResponse> updatePlanStatus(
            @Parameter(description = "Cycle count plan identifier", required = true) @PathVariable UUID planId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Target lifecycle status for the plan transition.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Start counting", value = """
                                                                    {"status":"STARTED"}
                                                                    """)))
                    @jakarta.validation.Valid
                    @RequestBody
                    UpdateCycleCountPlanStatusRequest request) {
        return ResponseEntity.ok(cycleCountPlanService.updateStatus(planId, request.getStatus()));
    }
}

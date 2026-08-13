package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CreateCycleCountScheduleRequest;
import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleResponse;
import com.positivity.inventory.internal.dto.cyclecount.schedule.UpdateCycleCountScheduleRequest;
import com.positivity.inventory.service.CycleCountScheduleService;
import com.positivity.security.common.SecurityContextHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Recurring cycle-count schedule endpoints (odoo-parity I1, issue #1031). */
@RestController
@RequestMapping("/v1/inventory/cycleCountSchedules")
@RequiredArgsConstructor
@Validated
@Tag(name = "Cycle Count Schedules", description = "Recurring cycle-count schedule management endpoints")
public class CycleCountScheduleController {

    private final CycleCountScheduleService cycleCountScheduleService;

    @PostMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SCHEDULE_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:initiate"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:initiate')")
    @Operation(
            operationId = "createCycleCountSchedule",
            summary = "Create cycle count schedule",
            description = """
                    Creates an active recurring cycle-count schedule for a location, optionally filtered to one \
                    zone and/or SKU category, that comes due every frequencyDays.
                    Use this tool to institutionalise periodic counting; do not use createCycleCountPlan, which \
                    creates a single one-off plan.
                    Preconditions: the caller must be an authenticated user (recorded as the schedule creator).
                    Required inputs: locationId (UUID), frequencyDays (positive integer) and nextDueDate (ISO \
                    date); zoneId and skuCategory are optional filters, and autoCreatePlan defaults to false, \
                    meaning the schedule only surfaces in the due-for-count view instead of auto-creating plans.
                    Emits an INVENTORY_CYCLE_COUNT_SCHEDULE_CREATE event; the schedule starts active and no plan \
                    is created by this call.
                    Returns 400 when locationId or nextDueDate is missing or frequencyDays is not positive.
                    """,
            tags = {"Cycle Count Schedules"})
    @ApiResponse(
            responseCode = "201",
            description = "Cycle count schedule created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountScheduleResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    public ResponseEntity<CycleCountScheduleResponse> createSchedule(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Recurring schedule to create, with its location, cadence, first due"
                                    + " date, and optional zone/category filters.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Monthly auto-creating schedule",
                                                            value = """
                                                                    {"locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "zoneId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "skuCategory":"BRAKES",
                                                                     "frequencyDays":30,
                                                                     "nextDueDate":"2026-09-01",
                                                                     "autoCreatePlan":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    CreateCycleCountScheduleRequest request) {
        String createdBy = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException("No current user"));
        CycleCountScheduleResponse response = cycleCountScheduleService.createSchedule(request, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SCHEDULE_LIST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
    @Operation(
            operationId = "listCycleCountSchedules",
            summary = "List cycle count schedules",
            description = """
                    Returns one page of recurring cycle-count schedules, newest first, optionally filtered by \
                    location and/or active flag.
                    Use this tool to discover scheduleIds or drive the due-for-count view via due=true, which \
                    restricts to active schedules whose nextDueDate has arrived; use getCycleCountSchedule instead \
                    when the scheduleId is already known.
                    Preconditions: none.
                    Required inputs: all query parameters are optional — locationId (UUID), active (boolean), due \
                    (boolean, default false), page (0-based, default 0) and size (default 50).
                    Emits an INVENTORY_CYCLE_COUNT_SCHEDULE_LIST audit event; no schedule state changes.
                    Returns 200 with an empty array when no schedules match, so an empty result is not an error \
                    condition.
                    """,
            tags = {"Cycle Count Schedules"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count schedules returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = CycleCountScheduleResponse.class))))
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    public ResponseEntity<List<CycleCountScheduleResponse>> listSchedules(
            @Parameter(description = "Filter by location identifier") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Filter by active flag") @RequestParam(required = false) Boolean active,
            @Parameter(description = "Restrict to active schedules currently due for counting")
                    @RequestParam(defaultValue = "false")
                    boolean due,
            @Parameter(description = "Page index (0-based)") @PositiveOrZero @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size") @Positive @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(cycleCountScheduleService.listSchedules(locationId, active, due, page, size));
    }

    @GetMapping("/{scheduleId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:view"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:view')")
    @Operation(
            operationId = "getCycleCountSchedule",
            summary = "Get cycle count schedule",
            description = """
                    Returns one recurring cycle-count schedule with its filters, frequency, next due date, and \
                    active and auto-create flags.
                    Use this tool when the scheduleId is already known; use listCycleCountSchedules instead to \
                    search by location or due state.
                    Preconditions: the schedule must exist.
                    Required inputs: scheduleId (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no cycle count schedule exists for the supplied id.
                    """,
            tags = {"Cycle Count Schedules"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count schedule returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountScheduleResponse.class)))
    @ApiResponse(responseCode = "404", description = "Cycle count schedule not found")
    public ResponseEntity<CycleCountScheduleResponse> getSchedule(
            @Parameter(description = "Cycle count schedule identifier", required = true) @PathVariable
                    UUID scheduleId) {
        return ResponseEntity.ok(cycleCountScheduleService.getSchedule(scheduleId));
    }

    @PutMapping("/{scheduleId}")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SCHEDULE_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:initiate"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:initiate')")
    @Operation(
            operationId = "updateCycleCountSchedule",
            summary = "Update cycle count schedule",
            description = """
                    Partially updates a recurring cycle-count schedule: frequencyDays, zoneId, skuCategory, \
                    nextDueDate, autoCreatePlan and active can each be changed, and null fields are left \
                    unchanged.
                    Use this tool to retune an existing schedule; do not use updateCycleCountPlanStatus, which \
                    moves an individual plan through its lifecycle, and note that v1 offers no way to clear the \
                    zone or SKU-category filters — recreate the schedule instead.
                    Preconditions: the schedule must exist.
                    Required inputs: scheduleId (UUID) path parameter and a body carrying only the fields to \
                    change; frequencyDays, when supplied, must be positive.
                    Emits an INVENTORY_CYCLE_COUNT_SCHEDULE_UPDATE event; the change takes effect on the next \
                    scheduler pass.
                    Returns 404 when the schedule does not exist, and 400 when a supplied frequencyDays is not \
                    positive.
                    """,
            tags = {"Cycle Count Schedules"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count schedule updated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountScheduleResponse.class)))
    @ApiResponse(responseCode = "400", description = "Validation failure")
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    @ApiResponse(responseCode = "404", description = "Cycle count schedule not found")
    public ResponseEntity<CycleCountScheduleResponse> updateSchedule(
            @Parameter(description = "Cycle count schedule identifier", required = true) @PathVariable UUID scheduleId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Partial update; only non-null fields are applied to the schedule.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Tighten cadence", value = """
                                                                    {"frequencyDays":14,
                                                                     "nextDueDate":"2026-09-01",
                                                                     "active":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpdateCycleCountScheduleRequest request) {
        return ResponseEntity.ok(cycleCountScheduleService.updateSchedule(scheduleId, request));
    }

    @DeleteMapping("/{scheduleId}")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SCHEDULE_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:initiate"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:initiate')")
    @Operation(
            operationId = "deactivateCycleCountSchedule",
            summary = "Deactivate cycle count schedule",
            description = """
                    Deactivates a recurring cycle-count schedule — a soft delete that clears its active flag so it \
                    stops firing and drops out of the due-for-count view; the row is never removed.
                    Use this tool to retire a schedule; to change its cadence or filters while keeping it running \
                    (or to re-activate it), use updateCycleCountSchedule instead.
                    Preconditions: the schedule must exist; deactivating an already-inactive schedule succeeds \
                    unchanged.
                    Required inputs: scheduleId (UUID) as a path parameter; there is no request body or \
                    confirmation flag.
                    Emits an INVENTORY_CYCLE_COUNT_SCHEDULE_DEACTIVATE event; plans already created from the \
                    schedule are untouched.
                    Returns 404 when no cycle count schedule exists for the supplied id.
                    """,
            tags = {"Cycle Count Schedules"})
    @ApiResponse(
            responseCode = "200",
            description = "Cycle count schedule deactivated",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = CycleCountScheduleResponse.class)))
    @ApiResponse(responseCode = "403", description = "User lacks required permission")
    @ApiResponse(responseCode = "404", description = "Cycle count schedule not found")
    public ResponseEntity<CycleCountScheduleResponse> deactivateSchedule(
            @Parameter(description = "Cycle count schedule identifier", required = true) @PathVariable
                    UUID scheduleId) {
        return ResponseEntity.ok(cycleCountScheduleService.deactivateSchedule(scheduleId));
    }
}

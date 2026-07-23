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
            summary = "Create cycle count schedule",
            description = "Creates a recurring cycle-count schedule for a location.",
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
            @Valid @RequestBody CreateCycleCountScheduleRequest request) {
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
            summary = "List cycle count schedules",
            description = "Lists one page of cycle-count schedules, newest first, optionally filtered by location"
                    + " and/or active flag. due=true restricts to active schedules whose next due date has arrived"
                    + " (the due-for-count view).",
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
            summary = "Get cycle count schedule",
            description = "Returns a cycle-count schedule by identifier.",
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
            summary = "Update cycle count schedule",
            description = "Partially updates a cycle-count schedule (frequency, filters, next due date, auto-create,"
                    + " active flag); null fields are left unchanged.",
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
            @Valid @RequestBody UpdateCycleCountScheduleRequest request) {
        return ResponseEntity.ok(cycleCountScheduleService.updateSchedule(scheduleId, request));
    }

    @DeleteMapping("/{scheduleId}")
    @EmitEvent(id = "INVENTORY_CYCLE_COUNT_SCHEDULE_DEACTIVATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:cycle_count:initiate"})
    @PreAuthorize("hasAuthority('inventory:cycle_count:initiate')")
    @Operation(
            summary = "Deactivate cycle count schedule",
            description = "Soft delete: marks the schedule inactive so it stops firing; the row is never removed.",
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

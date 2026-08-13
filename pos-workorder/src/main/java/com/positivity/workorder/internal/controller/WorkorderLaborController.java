package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.AdjustLaborRequest;
import com.positivity.workorder.internal.dto.StartLaborRequest;
import com.positivity.workorder.internal.dto.WorkorderLaborEntryResponse;
import com.positivity.workorder.internal.dto.WorkorderLaborMapper;
import com.positivity.workorder.service.WorkorderLaborService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for labor tracking on workorders.
 *
 * <p>
 * Implements CAP-005 Story #159 - Record Labor Performed
 */
@Tag(name = "Workorder Labor API", description = "Endpoints for tracking labor performed on workorders")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
public class WorkorderLaborController {

    private final WorkorderLaborService laborService;

    private static final String SYSTEM_USERNAME = "system";

    /**
     * Start a labor session on a workorder service.
     */
    @Operation(
            operationId = "startLaborSession",
            summary = "Start Labor Session on Service",
            description = """
                    Starts a labor entry tracking a technician's time against one workorder service line, \
                    stamping the start time and zero hours worked.
                    Use this tool when a technician begins billable labor on a specific service; do not use \
                    startWorkexecWorkSession, which is the payroll timekeeping clock rather than per-service labor \
                    tracking.
                    Preconditions: the workorder must be in ASSIGNED, WORK_IN_PROGRESS, AWAITING_PARTS, or \
                    AWAITING_APPROVAL status, the service must belong to that workorder, and the service must \
                    have no labor session still open.
                    Required inputs: workorderId and serviceId (UUIDs) as path parameters and technicianId \
                    (UUID) in the body; notes are optional, and an Idempotency-Key header makes retries return \
                    the original entry with 200 instead of 201.
                    Emits a WORKORDER_LABOR_START event.
                    Returns 201 with the new entry (200 on an idempotent replay), 404 when the workorder or \
                    service cannot be found, and 400 when a session is already active or the status disallows \
                    labor.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Labor session started successfully",
                        content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
                @ApiResponse(
                        responseCode = "200",
                        description = "Idempotent request - existing session returned",
                        content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid state - active session exists or invalid status"),
                @ApiResponse(responseCode = "403", description = "Permission denied"),
                @ApiResponse(responseCode = "404", description = "Workorder or service not found")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Technician performing the labor and optional starting notes.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = StartLaborRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "startLabor",
                                            value =
                                                    "{\"technicianId\":\"550e8400-e29b-41d4-a716-446655440120\",\"notes\":\"Starting diagnostic work\"}")))
    @PostMapping("/{workorderId}/services/{serviceId}/labor/start")
    @EmitEvent(id = "WORKORDER_LABOR_START", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:add"})
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    public ResponseEntity<WorkorderLaborEntryResponse> startLaborSession(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Parameter(description = "ID of the service item", example = "550e8400-e29b-41d4-a716-446655440010")
                    @PathVariable
                    UUID serviceId,
            @Valid @RequestBody StartLaborRequest request,
            @Parameter(
                            description = "Optional idempotency key to prevent duplicate starts",
                            example = "labor-start-550e8400-e29b-41d4-a716-446655440001")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USERNAME);

        try {
            // Check if this is an idempotent replay
            boolean isIdempotent = idempotencyKey != null && !idempotencyKey.isBlank();

            var entry = laborService.startLaborSession(
                    workorderId, serviceId, request.getTechnicianId(), request.getNotes(), username, idempotencyKey);

            WorkorderLaborEntryResponse response = WorkorderLaborMapper.toResponse(entry);

            log.info(
                    "Started labor session {} for workorder {} service {} by technician {}",
                    response.getId(),
                    workorderId,
                    serviceId,
                    request.getTechnicianId());

            // Return 200 for idempotent replay, 201 for new creation
            HttpStatus status = isIdempotent ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(response);

        } catch (NoSuchElementException e) {
            log.warn("Start labor failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Start labor failed - invalid state: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Stop a labor session.
     */
    @Operation(
            operationId = "stopLaborSession",
            summary = "Stop Active Labor Session",
            description = """
                    Stops an open labor entry, stamping the end time and computing hours worked from the elapsed \
                    session time.
                    Use this tool when a technician finishes labor on a service; do not use adjustLaborHours, \
                    which corrects the hours on an already-stopped entry.
                    Preconditions: the labor entry must exist and still be open — an entry with an end time \
                    cannot be stopped again.
                    Required inputs: workorderId and entryId (UUIDs) as path parameters; there is no request \
                    body, and an Idempotency-Key header makes retried stops return the already-stopped entry.
                    Emits a WORKORDER_LABOR_STOP event.
                    Returns 404 when no labor entry exists for the id, and 400 when the session is already \
                    stopped.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Labor session stopped successfully",
                        content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
                @ApiResponse(responseCode = "400", description = "Session already stopped"),
                @ApiResponse(responseCode = "403", description = "Permission denied"),
                @ApiResponse(responseCode = "404", description = "Labor entry not found")
            })
    @PostMapping("/{workorderId}/labor/{entryId}/stop")
    @EmitEvent(id = "WORKORDER_LABOR_STOP", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:add"})
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    public ResponseEntity<WorkorderLaborEntryResponse> stopLaborSession(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Parameter(description = "ID of the labor entry to stop", example = "550e8400-e29b-41d4-a716-446655440100")
                    @PathVariable
                    UUID entryId,
            @Parameter(
                            description = "Optional idempotency key to prevent duplicate stops",
                            example = "labor-stop-550e8400-e29b-41d4-a716-446655440100")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        try {
            var entry = laborService.stopLaborSession(entryId, idempotencyKey);

            WorkorderLaborEntryResponse response = WorkorderLaborMapper.toResponse(entry);

            log.info(
                    "Stopped labor session {} for workorder {} - {} hours worked",
                    entryId,
                    workorderId,
                    response.getHoursWorked());

            return ResponseEntity.ok(response);

        } catch (NoSuchElementException e) {
            log.warn("Stop labor failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Stop labor failed - invalid state: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get labor history for a workorder.
     */
    @Operation(
            operationId = "getLaborHistory",
            summary = "Get Workorder Labor History",
            description = """
                    Returns every labor entry recorded against a workorder, ordered newest first, including open \
                    sessions, stopped sessions with computed hours, and manual adjustments.
                    Use this tool when reviewing time spent on a workorder; do not use getWorkorderDetail, which \
                    aggregates labor into per-service totals instead of listing entries.
                    Preconditions: none — an unknown workorderId simply yields an empty list.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the entries, possibly empty; no 404 is produced for unknown workorders.
                    """,
            responses = {
                @ApiResponse(responseCode = "200", description = "Labor history retrieved successfully"),
                @ApiResponse(responseCode = "403", description = "Permission denied")
            })
    @GetMapping("/{workorderId}/labor")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:view"})
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    public ResponseEntity<List<WorkorderLaborEntryResponse>> getLaborHistory(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId) {

        var entries = laborService.getLaborHistory(workorderId);

        List<WorkorderLaborEntryResponse> responses =
                entries.stream().map(WorkorderLaborEntryResponse::fromEntity).toList();

        log.debug("Retrieved {} labor entries for workorder {}", responses.size(), workorderId);

        return ResponseEntity.ok(responses);
    }

    /**
     * Manually adjust labor hours.
     */
    @Operation(
            operationId = "adjustLaborHours",
            summary = "Adjust Labor Entry Hours",
            description = """
                    Manually overrides the hours worked on a labor entry, recording the adjustment reason and \
                    the adjusting user for the audit trail.
                    Use this tool to correct recorded hours after a timesheet review; do not use \
                    stopLaborSession, which computes hours from elapsed time when a session ends.
                    Preconditions: the labor entry must exist; the entry retains its original times, with the \
                    adjusted hours and reason stored alongside them.
                    Required inputs: workorderId and entryId (UUIDs) as path parameters, plus hoursWorked \
                    (decimal) and adjustmentReason in the body; an Idempotency-Key header makes retries return \
                    the already-adjusted entry.
                    Emits a WORKORDER_LABOR_ADJUST event.
                    Returns 404 when no labor entry exists for the id, and 400 when the hours value is rejected \
                    as invalid.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Labor hours adjusted successfully",
                        content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid hours value"),
                @ApiResponse(responseCode = "403", description = "Permission denied"),
                @ApiResponse(responseCode = "404", description = "Labor entry not found")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Corrected hours and the reason justifying the manual adjustment.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = AdjustLaborRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "adjustLabor",
                                            value =
                                                    "{\"hoursWorked\":2.5,\"adjustmentReason\":\"Corrected after timesheet review\"}")))
    @PutMapping("/{workorderId}/labor/{entryId}/adjust")
    @EmitEvent(id = "WORKORDER_LABOR_ADJUST", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:labor:add"})
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    public ResponseEntity<WorkorderLaborEntryResponse> adjustLaborHours(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Parameter(
                            description = "ID of the labor entry to adjust",
                            example = "550e8400-e29b-41d4-a716-446655440100")
                    @PathVariable
                    UUID entryId,
            @Valid @RequestBody AdjustLaborRequest request,
            @Parameter(
                            description = "Optional idempotency key to prevent duplicate adjustments",
                            example = "labor-adjust-550e8400-e29b-41d4-a716-446655440100")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        try {
            String username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USERNAME);
            var entry = laborService.adjustLaborHours(
                    entryId, request.getHoursWorked(), request.getAdjustmentReason(), username, idempotencyKey);

            WorkorderLaborEntryResponse response = WorkorderLaborEntryResponse.fromEntity(entry);

            log.info(
                    "Adjusted labor entry {} to {} hours - reason: {}",
                    entryId,
                    request.getHoursWorked(),
                    request.getAdjustmentReason());

            return ResponseEntity.ok(response);

        } catch (NoSuchElementException e) {
            log.warn("Adjust labor failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Adjust labor failed - invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }
}

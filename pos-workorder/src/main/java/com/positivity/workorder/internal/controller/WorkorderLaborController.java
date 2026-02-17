package com.positivity.workorder.internal.controller;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

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

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.AdjustLaborRequest;
import com.positivity.workorder.internal.dto.StartLaborRequest;
import com.positivity.workorder.internal.dto.WorkorderLaborEntryResponse;
import com.positivity.workorder.internal.dto.WorkorderLaborMapper;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.service.WorkorderLaborService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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

    private static final UUID SYSTEM_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /**
     * Start a labor session on a workorder service.
     */
    @Operation(summary = "Start labor session", description = "Start tracking labor on a specific service item. Only one active session allowed per service.", responses = {
            @ApiResponse(responseCode = "201", description = "Labor session started successfully", content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
            @ApiResponse(responseCode = "200", description = "Idempotent request - existing session returned", content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid state - active session exists or invalid status"),
            @ApiResponse(responseCode = "403", description = "Permission denied"),
            @ApiResponse(responseCode = "404", description = "Workorder or service not found")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Start labor request", required = true, content = @Content(schema = @Schema(implementation = StartLaborRequest.class), examples = @ExampleObject(name = "startLabor", value = "{\"technicianId\":\"550e8400-e29b-41d4-a716-446655440120\",\"notes\":\"Starting diagnostic work\"}")))
    @PostMapping("/{workorderId}/services/{serviceId}/labor/start")
    @EmitEvent(id = "WORKORDER_LABOR_START", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    public ResponseEntity<WorkorderLaborEntryResponse> startLaborSession(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable UUID workorderId,
            @Parameter(description = "ID of the service item", example = "550e8400-e29b-41d4-a716-446655440010") @PathVariable UUID serviceId,
            @Valid @RequestBody StartLaborRequest request,
            @Parameter(description = "Optional idempotency key to prevent duplicate starts", example = "labor-start-550e8400-e29b-41d4-a716-446655440001") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Parameter(description = "User ID from gateway authentication", example = "550e8400-e29b-41d4-a716-446655440100") @RequestHeader(value = "X-User-Id", required = false) String userIdHeader) {

        UUID userId = resolveUserId(userIdHeader);

        try {
            // Check if this is an idempotent replay
            boolean isIdempotent = idempotencyKey != null && !idempotencyKey.isBlank();

            WorkorderLaborEntry entry = laborService.startLaborSession(
                    workorderId,
                    serviceId,
                    request.getTechnicianId(),
                    request.getNotes(),
                    userId,
                    idempotencyKey);

            WorkorderLaborEntryResponse response = WorkorderLaborMapper.toResponse(entry);

            log.info("Started labor session {} for workorder {} service {} by technician {}",
                    response.getId(), workorderId, serviceId, request.getTechnicianId());

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
    @Operation(summary = "Stop labor session", description = "Stop an active labor session and calculate hours worked.", responses = {
            @ApiResponse(responseCode = "200", description = "Labor session stopped successfully", content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Session already stopped"),
            @ApiResponse(responseCode = "403", description = "Permission denied"),
            @ApiResponse(responseCode = "404", description = "Labor entry not found")
    })
    @PostMapping("/{workorderId}/labor/{entryId}/stop")
    @EmitEvent(id = "WORKORDER_LABOR_STOP", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    public ResponseEntity<WorkorderLaborEntryResponse> stopLaborSession(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable UUID workorderId,
            @Parameter(description = "ID of the labor entry to stop", example = "550e8400-e29b-41d4-a716-446655440100") @PathVariable UUID entryId,
            @Parameter(description = "Optional idempotency key to prevent duplicate stops", example = "labor-stop-550e8400-e29b-41d4-a716-446655440100") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        try {
            var entry = laborService.stopLaborSession(entryId, idempotencyKey);

            WorkorderLaborEntryResponse response = WorkorderLaborMapper.toResponse(entry);

            log.info("Stopped labor session {} for workorder {} - {} hours worked",
                    entryId, workorderId, response.getHoursWorked());

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
    @Operation(summary = "Get labor history", description = "Retrieve all labor entries for a workorder, ordered newest first.", responses = {
            @ApiResponse(responseCode = "200", description = "Labor history retrieved successfully"),
            @ApiResponse(responseCode = "403", description = "Permission denied")
    })
    @GetMapping("/{workorderId}/labor")
    @PreAuthorize("hasAuthority('workorder:labor:view')")
    public ResponseEntity<List<WorkorderLaborEntryResponse>> getLaborHistory(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable UUID workorderId) {

        List<WorkorderLaborEntry> entries = laborService.getLaborHistory(workorderId);

        List<WorkorderLaborEntryResponse> responses = entries.stream()
                .map(WorkorderLaborEntryResponse::fromEntity)
                .toList();

        log.debug("Retrieved {} labor entries for workorder {}", responses.size(), workorderId);

        return ResponseEntity.ok(responses);
    }

    /**
     * Manually adjust labor hours.
     */
    @Operation(summary = "Adjust labor hours", description = "Manually adjust hours worked on a labor entry with a reason for audit trail.", responses = {
            @ApiResponse(responseCode = "200", description = "Labor hours adjusted successfully", content = @Content(schema = @Schema(implementation = WorkorderLaborEntryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid hours value"),
            @ApiResponse(responseCode = "403", description = "Permission denied"),
            @ApiResponse(responseCode = "404", description = "Labor entry not found")
    })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Adjust labor request", required = true, content = @Content(schema = @Schema(implementation = AdjustLaborRequest.class), examples = @ExampleObject(name = "adjustLabor", value = "{\"hoursWorked\":2.5,\"adjustmentReason\":\"Corrected after timesheet review\"}")))
    @PutMapping("/{workorderId}/labor/{entryId}/adjust")
    @EmitEvent(id = "WORKORDER_LABOR_ADJUST", apiVersion = "1")
    @PreAuthorize("hasAuthority('workorder:labor:add')")
    public ResponseEntity<WorkorderLaborEntryResponse> adjustLaborHours(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001") @PathVariable UUID workorderId,
            @Parameter(description = "ID of the labor entry to adjust", example = "550e8400-e29b-41d4-a716-446655440100") @PathVariable UUID entryId,
            @Valid @RequestBody AdjustLaborRequest request,
            @Parameter(description = "Optional idempotency key to prevent duplicate adjustments", example = "labor-adjust-550e8400-e29b-41d4-a716-446655440100") @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        try {
            WorkorderLaborEntry entry = laborService.adjustLaborHours(
                    entryId,
                    request.getHoursWorked(),
                    request.getAdjustmentReason(),
                    idempotencyKey);

            WorkorderLaborEntryResponse response = WorkorderLaborEntryResponse.fromEntity(entry);

            log.info("Adjusted labor entry {} to {} hours - reason: {}",
                    entryId, request.getHoursWorked(), request.getAdjustmentReason());

            return ResponseEntity.ok(response);

        } catch (NoSuchElementException e) {
            log.warn("Adjust labor failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            log.warn("Adjust labor failed - invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    private UUID resolveUserId(String userIdHeader) {
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            try {
                return UUID.fromString(userIdHeader);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid X-User-Id header: {}", userIdHeader);
            }
        }
        return SYSTEM_USER_ID;
    }
}

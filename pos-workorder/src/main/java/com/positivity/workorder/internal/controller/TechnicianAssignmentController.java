package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.AssignTechnicianRequest;
import com.positivity.workorder.internal.dto.ReassignTechnicianRequest;
import com.positivity.workorder.internal.dto.TechnicianAssignmentMapper;
import com.positivity.workorder.internal.dto.TechnicianAssignmentResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.service.TechnicianAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for managing technician assignments to workorders.
 *
 * <p>
 * Implements CAP-005 Story #161 - Assign Technician to Workorder
 */
@Tag(name = "Technician Assignment API", description = "Endpoints for assigning technicians to workorders")
@RestController
@RequestMapping("/v1/workorders")
@RequiredArgsConstructor
@Slf4j
public class TechnicianAssignmentController {

    private final TechnicianAssignmentService assignmentService;

    private static final String SYSTEM_USERNAME = "system";

    /**
     * Assign a technician to a workorder.
     *
     * <p>
     * This operation transitions the workorder to ASSIGNED status if it's currently
     * APPROVED.
     * Supports idempotency via Idempotency-Key header.
     */
    @Operation(
            operationId = "assignTechnician",
            summary = "Assign Technician to Workorder",
            description = """
                    Assigns a technician to a workorder as the current assignment, retiring any previous \
                    assignment into history and transitioning the workorder from APPROVED to ASSIGNED when \
                    applicable.
                    Use this tool for the first assignment on a workorder; do not use reassignTechnician, which \
                    requires an existing current assignment and records a reassignment reason.
                    Preconditions: the workorder must exist and be in APPROVED, ASSIGNED, or WORK_IN_PROGRESS \
                    status.
                    Required inputs: workorderId (UUID) as a path parameter and technicianId (UUID) in the body; \
                    notes are optional, the assignedByUserId body field is ignored in favor of the security \
                    context, and the Idempotency-Key header is accepted but not currently used to deduplicate.
                    Emits a WORKORDER_TECHNICIAN_ASSIGN event; an APPROVED workorder is transitioned to ASSIGNED \
                    with a recorded state transition.
                    Returns 404 when the workorder does not exist, and 400 with the failure reason when the \
                    workorder status does not allow assignment.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Technician assigned successfully",
                        content = @Content(schema = @Schema(implementation = TechnicianAssignmentResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid state transition"),
                @ApiResponse(responseCode = "403", description = "Permission denied"),
                @ApiResponse(responseCode = "404", description = "Workorder or technician not found")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Technician to place on the workorder, with optional assignment notes.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = AssignTechnicianRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "assignTechnician",
                                            value =
                                                    "{\"technicianId\":\"550e8400-e29b-41d4-a716-446655440120\",\"assignedByUserId\":\"550e8400-e29b-41d4-a716-446655440100\",\"notes\":\"Primary technician assigned\"}")))
    @PostMapping("/{workorderId}/technician")
    @EmitEvent(id = "WORKORDER_TECHNICIAN_ASSIGN", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:assign-technician"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_ASSIGN_TECHNICIAN + "')")
    public ResponseEntity<TechnicianAssignmentResponse> assignTechnician(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Valid @RequestBody AssignTechnicianRequest request,
            @Parameter(
                            description = "Optional idempotency key to prevent duplicate assignments",
                            example = "tech-assign-550e8400-e29b-41d4-a716-446655440001")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        String assignedBy = resolveAssignedByUsername();

        try {
            var assignment = assignmentService.assignTechnician(
                    workorderId, request.getTechnicianId(), assignedBy, request.getNotes());

            var workorderStatus = assignmentService.getWorkorderStatus(workorderId);
            var previousTechId = assignmentService.getPreviousTechnicianId(workorderId);

            TechnicianAssignmentResponse response = TechnicianAssignmentMapper.toAssignmentResponse(
                    assignment,
                    workorderStatus,
                    previousTechId.map(UUID::toString).orElse(null),
                    "Technician assigned successfully");

            log.info(
                    "Technician {} assigned to workorder {} by user {}",
                    request.getTechnicianId(),
                    workorderId,
                    assignedBy);

            return ResponseEntity.ok(response);

        } catch (NoSuchElementException e) {
            log.warn("Assignment failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Assignment failed - invalid state: {}", e.getMessage());
            // Surface the reason (e.g. workorder status not assignable) so the client can show
            // it instead of an empty 400.
            return ResponseEntity.badRequest().body(errorResponse(workorderId, e.getMessage()));
        }
    }

    /** Build a minimal response carrying just the failure reason for error status codes. */
    private TechnicianAssignmentResponse errorResponse(UUID workorderId, String message) {
        return TechnicianAssignmentResponse.builder()
                .workorderId(workorderId.toString())
                .message(message)
                .build();
    }

    /**
     * Reassign a workorder to a different technician.
     *
     * <p>
     * Requires an existing assignment. Records the reason for reassignment.
     */
    @Operation(
            operationId = "reassignTechnician",
            summary = "Reassign Workorder to Different Technician",
            description = """
                    Reassigns a workorder to a different technician, retiring the current assignment with the \
                    given reason and creating a new current assignment that preserves the full history.
                    Use this tool when a workorder already has a technician and must change hands; do not use \
                    assignTechnician, which is for the initial assignment and records no reassignment reason.
                    Preconditions: the workorder must exist, be in APPROVED, ASSIGNED, or WORK_IN_PROGRESS \
                    status, and have a current technician assignment to reassign from.
                    Required inputs: workorderId (UUID) as a path parameter and newTechnicianId (UUID) in the \
                    body; reason and notes are optional, the reassignedByUserId body field is ignored in favor \
                    of the security context, and the Idempotency-Key header is accepted but not currently used.
                    Emits a WORKORDER_TECHNICIAN_REASSIGN event.
                    Returns 404 when the workorder does not exist, and 400 with the failure reason when there is \
                    no current assignment or the workorder status does not allow reassignment.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Technician reassigned successfully",
                        content = @Content(schema = @Schema(implementation = TechnicianAssignmentResponse.class))),
                @ApiResponse(responseCode = "400", description = "Invalid state transition or no current assignment"),
                @ApiResponse(responseCode = "403", description = "Permission denied"),
                @ApiResponse(responseCode = "404", description = "Workorder not found")
            })
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "Replacement technician plus the reason the workorder is changing hands.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = ReassignTechnicianRequest.class),
                            examples =
                                    @ExampleObject(
                                            name = "reassignTechnician",
                                            value =
                                                    "{\"newTechnicianId\":\"550e8400-e29b-41d4-a716-446655440121\",\"reassignedByUserId\":\"550e8400-e29b-41d4-a716-446655440100\",\"reason\":\"Scheduling conflict\",\"notes\":\"Reassigned due to availability\"}")))
    @PutMapping("/{workorderId}/technician")
    @EmitEvent(id = "WORKORDER_TECHNICIAN_REASSIGN", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:assign-technician"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_ASSIGN_TECHNICIAN + "')")
    public ResponseEntity<TechnicianAssignmentResponse> reassignTechnician(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Valid @RequestBody ReassignTechnicianRequest request,
            @Parameter(
                            description = "Optional idempotency key to prevent duplicate reassignments",
                            example = "tech-reassign-550e8400-e29b-41d4-a716-446655440001")
                    @RequestHeader(value = "Idempotency-Key", required = false)
                    String idempotencyKey) {

        String reassignedBy = resolveAssignedByUsername();

        try {
            // Get previous technician ID via service method
            UUID previousTechId =
                    assignmentService.getPreviousTechnicianId(workorderId).orElse(null);

            var newAssignment = assignmentService.reassignTechnician(
                    workorderId, request.getNewTechnicianId(), reassignedBy, request.getReason(), request.getNotes());

            var workorderStatus = assignmentService.getWorkorderStatus(workorderId);

            TechnicianAssignmentResponse response = TechnicianAssignmentMapper.toReassignmentResponse(
                    newAssignment, previousTechId, workorderStatus, request.getReason(), reassignedBy);

            log.info(
                    "Workorder {} reassigned from technician {} to {} by user {}",
                    workorderId,
                    previousTechId,
                    request.getNewTechnicianId(),
                    reassignedBy);

            // Note: Notification to previous technician is handled by event listeners if
            // configured

            return ResponseEntity.ok(response);

        } catch (NoSuchElementException e) {
            log.warn("Reassignment failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            log.warn("Reassignment failed - invalid state: {}", e.getMessage());
            return ResponseEntity.badRequest().body(errorResponse(workorderId, e.getMessage()));
        }
    }

    /**
     * Get current assignment and history for a workorder.
     *
     * <p>
     * Returns the current technician assignment plus full assignment history.
     */
    @Operation(
            operationId = "getTechnicianAssignment",
            summary = "Get Current Technician Assignment",
            description = """
                    Returns the workorder's current technician assignment together with the full assignment \
                    history and the workorder's status.
                    Use this tool when checking who owns a workorder; do not use assignTechnician or \
                    reassignTechnician, which change the assignment rather than reading it.
                    Preconditions: the workorder must exist and have a current technician assignment.
                    Required inputs: workorderId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the workorder does not exist or when it has no current assignment.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Assignment retrieved successfully",
                        content = @Content(schema = @Schema(implementation = TechnicianAssignmentResponse.class))),
                @ApiResponse(responseCode = "404", description = "Workorder not found or no assignment exists")
            })
    @GetMapping("/{workorderId}/technician")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:view"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    public ResponseEntity<TechnicianAssignmentResponse> getTechnicianAssignment(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId) {

        try {
            // Get workorder status and current assignment
            var workorderStatus = assignmentService.getWorkorderStatus(workorderId);
            var currentAssignment = assignmentService.getCurrentAssignment(workorderId);

            if (currentAssignment.isEmpty()) {
                log.debug("No technician assignment found for workorder {}", workorderId);
                return ResponseEntity.notFound().build();
            }

            var history = assignmentService.getAssignmentHistory(workorderId);

            TechnicianAssignmentResponse response =
                    TechnicianAssignmentMapper.toResponseWithHistory(currentAssignment.get(), history, workorderStatus);

            return ResponseEntity.ok(response);

        } catch (NoSuchElementException e) {
            log.warn("Get assignment failed - not found: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Resolve assigned-by username from authentication context.
     */
    @NonNull
    private String resolveAssignedByUsername() {
        var username = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USERNAME);
        if (username.length() >= 2 && username.startsWith("\"") && username.endsWith("\"")) {
            return username.substring(1, username.length() - 1);
        }
        return username;
    }
}

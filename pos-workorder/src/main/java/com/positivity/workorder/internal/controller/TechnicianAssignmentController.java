package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.workorder.internal.dto.AssignTechnicianRequest;
import com.positivity.workorder.internal.dto.ReassignTechnicianRequest;
import com.positivity.workorder.internal.dto.TechnicianAssignmentMapper;
import com.positivity.workorder.internal.dto.TechnicianAssignmentResponse;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.TechnicianAssignmentService;
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
     * This operation transitions the workorder to ASSIGNED when it is APPROVED and
     * already stands on a bay or mobile unit; with nowhere to be worked it stays
     * APPROVED until it is placed.
     * Supports idempotency via Idempotency-Key header.
     */
    @Operation(
            operationId = "assignTechnician",
            summary = "Assign Technician to Workorder",
            description = """
                    Assigns a technician to a workorder that has none, transitioning the workorder from \
                    APPROVED to ASSIGNED when it also stands on a BAY or a MOBILE_UNIT.
                    Use this tool for the first assignment on a workorder; do not use it to change technicians \
                    — reassignTechnician requires an existing current assignment and records a reassignment \
                    reason, and releaseTechnician takes the current one off without a replacement.
                    Preconditions: the workorder must exist, be in APPROVED, ASSIGNED, or WORK_IN_PROGRESS \
                    status, and have no current technician.
                    Required inputs: workorderId (UUID) as a path parameter and technicianId (UUID) in the body; \
                    notes are optional, the assignedByUserId body field is ignored in favor of the security \
                    context, and the Idempotency-Key header is accepted but not currently used to deduplicate.
                    Emits a WORKORDER_TECHNICIAN_ASSIGN event; an APPROVED workorder that also holds a BAY or \
                    MOBILE_UNIT position is transitioned to ASSIGNED with a recorded state transition. ASSIGNED \
                    means both halves — a technician and somewhere to work — so a workorder with no position, or \
                    parked on HOLD, stays APPROVED until it is placed.
                    Returns 404 when the workorder does not exist, 400 with the failure reason when the \
                    workorder's status cannot take a technician yet, which includes a reopened COMPLETED one, \
                    409 WORKORDER_CLOSED when it is closed — CANCELLED, or COMPLETED and not reopened — 409 \
                    TECHNICIAN_ALREADY_ASSIGNED when the workorder already has a current technician, which \
                    reassignTechnician changes, and 422 TECHNICIAN_NOT_STAFFED_AT_SITE when the technician has \
                    one or more ACTIVE staffing assignments effective today and none of them is at the \
                    workorder's site — there is no override; have the technician staffed at this site in People, \
                    effective today, and retry.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Technician assigned successfully",
                        content = @Content(schema = @Schema(implementation = TechnicianAssignmentResponse.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid state transition",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Permission denied",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Workorder not found",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "409",
                        description = "The workorder is closed (ApiError.code WORKORDER_CLOSED) or already has "
                                + "a current technician (ApiError.code TECHNICIAN_ALREADY_ASSIGNED, with the "
                                + "current technician id as referenceId)",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "422",
                        description = "The technician is not known (ApiError.code TECHNICIAN_NOT_FOUND) or has "
                                + "ACTIVE staffing effective today at one or more sites but not this workorder's "
                                + "site (ApiError.code TECHNICIAN_NOT_STAFFED_AT_SITE, with the workorder's site "
                                + "id as referenceId and a fieldErrors entry on technicianId). No override "
                                + "exists for TECHNICIAN_NOT_STAFFED_AT_SITE; a technician with no ACTIVE "
                                + "staffing rows at all is allowed.",
                        content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                    Returns 404 when the workorder does not exist, 400 with the failure reason when the workorder's \
                    status cannot take a technician yet, which includes a reopened COMPLETED one, 409 \
                    WORKORDER_CLOSED when it is closed — CANCELLED, or COMPLETED and not reopened — 409 \
                    TECHNICIAN_NOT_ASSIGNED when the workorder has no current technician to reassign from, \
                    which assignTechnician creates, and 422 TECHNICIAN_NOT_STAFFED_AT_SITE when the new \
                    technician has one or more ACTIVE staffing assignments effective today and none of them is \
                    at the workorder's site — there is no override; have the technician staffed at this site in \
                    People, effective today, and retry.
                    """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Technician reassigned successfully",
                        content = @Content(schema = @Schema(implementation = TechnicianAssignmentResponse.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid state transition",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "403",
                        description = "Permission denied",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Workorder not found",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "409",
                        description = "The workorder is closed (ApiError.code WORKORDER_CLOSED) or has no current "
                                + "technician (ApiError.code TECHNICIAN_NOT_ASSIGNED)",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "422",
                        description = "The new technician is not known (ApiError.code TECHNICIAN_NOT_FOUND) or "
                                + "has ACTIVE staffing effective today at one or more sites but not this "
                                + "workorder's site (ApiError.code TECHNICIAN_NOT_STAFFED_AT_SITE, with the "
                                + "workorder's site id as referenceId and a fieldErrors entry on technicianId). "
                                + "No override exists for TECHNICIAN_NOT_STAFFED_AT_SITE; a technician with no "
                                + "ACTIVE staffing rows at all is allowed.",
                        content = @Content(schema = @Schema(implementation = ApiError.class)))
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
                @ApiResponse(
                        responseCode = "404",
                        description = "Workorder not found or no assignment exists",
                        content = @Content(schema = @Schema(implementation = ApiError.class)))
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
     * Release the current technician, leaving the workorder unassigned.
     *
     * <p>
     * The counterpart of assign that #1983 found missing: freeing a technician
     * without naming a replacement.
     */
    @Operation(
            operationId = "releaseTechnician",
            summary = "Release a Workorder's Current Technician",
            description = """
                    Releases the workorder's current technician, closing the assignment in history and leaving \
                    the workorder with nobody on it.
                    Use this tool when a technician comes off a job without a replacement — capacity freed for a \
                    workorder that is blocked or parked; do not use reassignTechnician, which requires a \
                    replacement, and do not use it to change technicians.
                    Preconditions: the workorder must exist and must not be COMPLETED or CANCELLED; \
                    releasing a workorder that has no current technician succeeds and writes nothing, so the \
                    call is idempotent.
                    Required inputs: workorderId (UUID) as a path parameter; reason is an optional query \
                    parameter recorded on the closed assignment.
                    Emits a WORKORDER_TECHNICIAN_RELEASE event. An ASSIGNED workorder left with nobody on it \
                    moves back to APPROVED, with a status transition recorded and a status event published; a \
                    workorder that has already started keeps its status.
                    Returns 404 when the workorder does not exist, and 409 WORKORDER_CLOSED when it is \
                    COMPLETED or CANCELLED.
                    """,
            responses = {
                @ApiResponse(responseCode = "204", description = "Technician released, or none was assigned"),
                @ApiResponse(
                        responseCode = "403",
                        description = "Permission denied",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Workorder not found",
                        content = @Content(schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "409",
                        description = "Workorder is COMPLETED or CANCELLED (ApiError.code WORKORDER_CLOSED)",
                        content = @Content(schema = @Schema(implementation = ApiError.class)))
            })
    @DeleteMapping("/{workorderId}/technician")
    @EmitEvent(id = "WORKORDER_TECHNICIAN_RELEASE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"workorder:workorder:assign-technician"})
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_ASSIGN_TECHNICIAN + "')")
    public ResponseEntity<Void> releaseTechnician(
            @Parameter(description = "ID of the workorder", example = "550e8400-e29b-41d4-a716-446655440001")
                    @PathVariable
                    UUID workorderId,
            @Parameter(description = "Why the technician is coming off the workorder", example = "Shift ended")
                    @RequestParam(value = "reason", required = false)
                    String reason) {

        String releasedBy = resolveAssignedByUsername();
        // Existence is checked before the release so a release against an unknown workorder is a 404
        // rather than a silent 204 — releaseAssignment itself answers "nothing to release" and
        // "no such workorder" identically, which is right for its internal callers and wrong here.
        // The same call carries the closed-lifecycle guard the position release has: a COMPLETED or
        // CANCELLED workorder answers 409 WORKORDER_CLOSED rather than quietly closing an assignment
        // on a finished job (#1983).
        assignmentService.requireOpenWorkorder(workorderId);
        assignmentService.releaseAssignment(workorderId, releasedBy, reason);
        log.info("Released the technician on workorder {} by user {}", workorderId, releasedBy);
        return ResponseEntity.noContent().build();
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

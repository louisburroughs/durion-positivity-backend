package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.service.AssignmentService;
import com.positivity.shopmanager.service.dto.AssignmentResponse;
import com.positivity.shopmanager.service.dto.CreateAssignmentRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/appointments/{appointmentId}/assignments")
@RequiredArgsConstructor
@Tag(
        name = "Appointment Assignments",
        description = "Create and list technician and resource assignments for an appointment")
public class AssignmentController {

    private final AssignmentService assignmentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"shop:bay:assign"})
    @PreAuthorize("hasAuthority('shop:bay:assign')")
    @EmitEvent(id = "SHOPMGR_ASSIGNMENT_CREATED", apiVersion = "1")
    @Operation(
            operationId = "createAssignment",
            summary = "Create Mechanic Assignments for an Appointment",
            description = """
                    Creates the mechanic assignment for a scheduled appointment in CONFIRMED status, linking one \
                    LEAD mechanic and optional ASSIST mechanics and optionally reserving a bay or mobile-unit \
                    resource.
                    Use this tool when staffing a booked appointment; do not use executeConflictOverride, which \
                    records a schedule-conflict bypass without assigning anyone, and use listAssignments to read \
                    what is already assigned.
                    Preconditions: the appointment must exist and be in SCHEDULED status, no CONFIRMED or \
                    IN_PROGRESS assignment may already exist for it, and every mechanicPersonId must resolve to a \
                    known mechanic.
                    Required inputs: mechanics with exactly one LEAD role (a single mechanic with a null role \
                    defaults to LEAD); resourceId and resourceType are optional, and override=true requires the \
                    shop:schedule:edit authority plus a non-blank overrideReason.
                    Emits a SHOPMGR_ASSIGNMENT_CREATED event and persists the assignment and its mechanic links.
                    Returns 400 when the appointment or a mechanic cannot be resolved, the LEAD constraint is \
                    violated, or overrideReason is blank with override=true, and 403 when override is requested \
                    without the shop:schedule:edit authority.
                    """)
    @ApiResponse(
            responseCode = "201",
            description = "Assignments created",
            content = @Content(schema = @Schema(implementation = AssignmentResponse.class)))
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public @NonNull AssignmentResponse createAssignment(
            @Parameter(description = "Appointment identifier", required = true) @PathVariable UUID appointmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Mechanics to assign with their roles plus an optional bay or mobile-unit"
                                    + " resource; the appointmentId in the path takes precedence over the body.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Single lead mechanic with a bay",
                                                            value = """
                                                                    {"appointmentId":"01960003-0000-7000-8000-000000000001",
                                                                     "mechanics":[{"mechanicPersonId":"01960003-0000-7000-8000-000000000010",
                                                                                   "role":"LEAD"}],
                                                                     "resourceId":"01960003-0000-7000-8000-000000000005",
                                                                     "resourceType":"BAY",
                                                                     "override":false}
                                                                    """)))
                    @RequestBody
                    @NonNull
                    CreateAssignmentRequest request) {
        var augmented = CreateAssignmentRequest.builder()
                .appointmentId(appointmentId)
                .mechanics(request.getMechanics())
                .resourceId(request.getResourceId())
                .resourceType(request.getResourceType())
                .override(request.isOverride())
                .overrideReason(request.getOverrideReason())
                .build();
        return assignmentService.create(augmented);
    }

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"appointments:view", "shop:schedule:view"})
    @PreAuthorize("hasAnyAuthority('appointments:view', 'shop:schedule:view')")
    @EmitEvent(id = "SHOPMGR_ASSIGNMENT_LIST_FETCHED", apiVersion = "1")
    @Operation(operationId = "listAssignments", summary = "List Assignments for an Appointment", description = """
                    Returns all assignments recorded for an appointment, including each mechanic's role, the \
                    reserved resource, status and override flag.
                    Use this tool when reading the staffing of a known appointment; do not use createAssignment, \
                    which creates a new assignment, and use searchShopAudit for the historical change trail.
                    Preconditions: none beyond authorization; an unknown appointmentId is not rejected.
                    Required inputs: appointmentId (UUID) as a path parameter; there is no request body and no \
                    filtering.
                    Emits a SHOPMGR_ASSIGNMENT_LIST_FETCHED audit event; no state changes occur, this is a \
                    read-only projection.
                    Returns 200 with an empty list when the appointment has no assignments or does not exist; no \
                    404 is raised for an unknown appointmentId.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Assignments returned",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AssignmentResponse.class))))
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public @NonNull List<AssignmentResponse> listAssignments(
            @Parameter(description = "Appointment identifier", required = true) @PathVariable UUID appointmentId) {
        return assignmentService.getByAppointmentId(appointmentId);
    }
}

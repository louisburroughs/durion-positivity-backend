package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import com.positivity.people.service.StaffingAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(
        name = "People - Staffing Assignments",
        description = "Person-to-location staffing assignment operations (CAP-119)")
@RestController
@RequestMapping("/v1/people")
@RequiredArgsConstructor
public class StaffingAssignmentController {

    private static final String ASSIGNMENTS = "/staffing/assignments";

    private final StaffingAssignmentService staffingAssignmentService;

    @Operation(
            operationId = "createStaffingAssignment",
            summary = "Create Person To Location Staffing Assignment",
            description = """
                    Creates a staffing assignment linking a person to a location with a role, effective dates, and a \
                    primary flag.
                    Use this tool when a person starts working at a location; do not use updateStaffingAssignment, \
                    which modifies an existing assignment by id.
                    Preconditions: the person must exist in the identity replica with an ACTIVE employee record, the \
                    location must be active, and no assignment for the same person, location, and role may overlap \
                    the effective dates.
                    Required inputs: personId (UUID), locationId (UUID), role, isPrimary, and effectiveFrom \
                    (yyyy-MM-dd); effectiveTo is optional and open-ended when null.
                    Emits a PEOPLE_STAFFING_ASSIGNMENT_CREATE event and publishes a staffing-assignment fact; a new \
                    primary demotes and ends any overlapping existing primary, and a person's first active \
                    assignment is forced primary regardless of the flag.
                    Returns 409 when an overlapping assignment exists for the person, location, and role, 404 when \
                    the person, employee record, or active location cannot be resolved, and 400 when the person's \
                    employee status is not ACTIVE.
                    """)
    @ApiResponse(responseCode = "201", description = "Assignment created.")
    @ApiResponse(responseCode = "400", description = "Validation error.")
    @ApiResponse(responseCode = "404", description = "Location or person not found.")
    @ApiResponse(responseCode = "409", description = "Overlapping assignment exists.")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_CREATE", apiVersion = "1")
    @PostMapping(ASSIGNMENTS)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('people:employee:edit')")
    public ResponseEntity<StaffingAssignmentResponse> createAssignment(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Staffing assignment to create, binding one person to one location for a"
                                    + " role over an effective date range.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Primary technician assignment",
                                                            value = """
                                                                    {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "role":"TECHNICIAN","isPrimary":true,
                                                                     "effectiveFrom":"2026-02-16"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    CreateStaffingAssignmentRequest request,
            @AuthenticationPrincipal String actor) {
        StaffingAssignmentResponse response =
                staffingAssignmentService.create(request, actor != null ? actor : "system");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            operationId = "listStaffingAssignments",
            summary = "List Staffing Assignments For A Person",
            description = """
                    Lists every staffing assignment for a person, including ENDED ones, across all locations and \
                    roles.
                    Use this tool for assignment history and administration; use listPersonLocations instead when \
                    only the assignments active today are wanted.
                    Preconditions: none beyond authentication; an unknown personId simply yields no rows.
                    Required inputs: personId (UUID) as a query parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the person has no assignments.
                    """)
    @ApiResponse(responseCode = "200", description = "List of assignments.")
    @GetMapping(ASSIGNMENTS)
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('people:employee:view')")
    public List<StaffingAssignmentResponse> getAssignments(@RequestParam @NonNull UUID personId) {
        return staffingAssignmentService.findByPersonId(personId);
    }

    @Operation(
            operationId = "listStaffingAssignmentsByPath",
            summary = "List Staffing Assignments By Person Path",
            description = """
                    Lists every staffing assignment for the person given in the path, including ENDED ones; the data \
                    is identical to listStaffingAssignments.
                    Use this tool when a path-style URL is preferred; use listStaffingAssignments instead for the \
                    query-parameter form, which returns the same rows.
                    Preconditions: none beyond authentication; an unknown personId simply yields no rows.
                    Required inputs: personId (UUID) path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with an empty list when the person has no assignments.
                    """)
    @ApiResponse(responseCode = "200", description = "List of assignments.")
    @GetMapping("/{personId}/staffing/assignments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('people:employee:view')")
    public List<StaffingAssignmentResponse> getAssignmentsByPath(@PathVariable @NonNull UUID personId) {
        return staffingAssignmentService.findByPersonId(personId);
    }

    @Operation(operationId = "getStaffingAssignment", summary = "Get Staffing Assignment By Id", description = """
                    Returns a single staffing assignment by its unique assignment id.
                    Use this tool when the assignment id is already known; use listStaffingAssignments instead to \
                    discover a person's assignments.
                    Preconditions: the assignment must exist; both ACTIVE and ENDED assignments are returned.
                    Required inputs: assignmentId (UUID) path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no assignment exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Assignment found.")
    @ApiResponse(responseCode = "404", description = "Assignment not found.")
    @GetMapping(ASSIGNMENTS + "/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('people:employee:view')")
    public ResponseEntity<StaffingAssignmentResponse> getAssignment(@PathVariable @NonNull UUID assignmentId) {
        return staffingAssignmentService
                .findById(assignmentId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            operationId = "updateStaffingAssignment",
            summary = "Update An Existing Staffing Assignment",
            description = """
                    Replaces a staffing assignment's person, location, role, primary flag, and effective dates.
                    Use this tool to correct or reshape an existing assignment; do not use endStaffingAssignment, \
                    which only closes an assignment, and do not use createStaffingAssignment for a new one.
                    Preconditions: the assignment must exist, the person must exist with an ACTIVE employee record, \
                    the location must be active, and no other assignment for the same person, location, and role may \
                    overlap the effective dates.
                    Required inputs: assignmentId (UUID) path parameter plus the full body (personId, locationId, \
                    role, isPrimary, effectiveFrom); this is a full replacement, not a patch.
                    Emits a PEOPLE_STAFFING_ASSIGNMENT_UPDATE event and publishes a staffing-assignment fact; \
                    setting isPrimary true demotes and ends any other overlapping primary assignment.
                    Returns 404 when the assignment, person, employee record, or active location cannot be resolved, \
                    409 when another assignment overlaps for the person, location, and role, and 400 when the \
                    person's employee status is not ACTIVE.
                    """)
    @ApiResponse(responseCode = "200", description = "Assignment updated.")
    @ApiResponse(responseCode = "400", description = "Validation error.")
    @ApiResponse(responseCode = "404", description = "Assignment not found.")
    @ApiResponse(responseCode = "409", description = "Overlapping assignment exists.")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_UPDATE", apiVersion = "1")
    @PutMapping(ASSIGNMENTS + "/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('people:employee:edit')")
    public ResponseEntity<StaffingAssignmentResponse> updateAssignment(
            @PathVariable @NonNull UUID assignmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Full replacement values for the assignment's person, location, role,"
                                    + " primary flag, and effective dates.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Extend assignment end date", value = """
                                                                    {"personId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                                     "role":"TECHNICIAN","isPrimary":true,
                                                                     "effectiveFrom":"2026-02-16",
                                                                     "effectiveTo":"2026-12-31"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    UpdateStaffingAssignmentRequest request,
            @AuthenticationPrincipal String actor) {
        return staffingAssignmentService
                .update(assignmentId, request, actor != null ? actor : "system")
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(operationId = "endStaffingAssignment", summary = "End An Active Staffing Assignment", description = """
                    Ends a staffing assignment by setting its status to ENDED without physically deleting the row.
                    Use this tool when a person stops working at a location; do not use disableEmployee, which \
                    offboards the whole employee, and do not use updateStaffingAssignment to shorten dates manually.
                    Preconditions: the assignment must exist; ending an already ENDED assignment is accepted and \
                    republishes the fact.
                    Required inputs: assignmentId (UUID) path parameter; there is no request body.
                    Emits a PEOPLE_STAFFING_ASSIGNMENT_END event and publishes a staffing-assignment fact; a null \
                    effectiveTo is stamped with today's date.
                    Returns 204 on success, and 404 when no assignment exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "204", description = "Assignment ended.")
    @ApiResponse(responseCode = "404", description = "Assignment not found.")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_END", apiVersion = "1")
    @DeleteMapping(ASSIGNMENTS + "/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('people:employee:edit')")
    public ResponseEntity<Void> endAssignment(@PathVariable @NonNull UUID assignmentId) {
        staffingAssignmentService.end(assignmentId);
        return ResponseEntity.noContent().build();
    }
}

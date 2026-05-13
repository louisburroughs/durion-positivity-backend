package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import com.positivity.people.service.StaffingAssignmentService;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/v1/people/staffing/assignments")
@RequiredArgsConstructor
public class StaffingAssignmentController {

    private final StaffingAssignmentService staffingAssignmentService;

    @Operation(
            summary = "Create staffing assignment",
            description = "Link a person to a location with role, effective dates, and primary flag.")
    @ApiResponse(responseCode = "201", description = "Assignment created.")
    @ApiResponse(responseCode = "400", description = "Validation error.")
    @ApiResponse(responseCode = "404", description = "Location or person not found.")
    @ApiResponse(responseCode = "409", description = "Overlapping assignment exists.")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_CREATE", apiVersion = "1")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('people:employee:edit')")
    public ResponseEntity<StaffingAssignmentResponse> createAssignment(
            @Valid @RequestBody @NonNull CreateStaffingAssignmentRequest request,
            @AuthenticationPrincipal String actor) {
        StaffingAssignmentResponse response =
                staffingAssignmentService.create(request, actor != null ? actor : "system");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(
            summary = "List assignments for person",
            description = "Returns all staffing assignments for the specified person.")
    @ApiResponse(responseCode = "200", description = "List of assignments.")
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:view"})
    @PreAuthorize("hasAuthority('people:employee:view')")
    public List<StaffingAssignmentResponse> getAssignments(@RequestParam @NonNull UUID personId) {
        return staffingAssignmentService.findByPersonId(personId);
    }

    @Operation(
            summary = "Get assignment by ID",
            description = "Retrieves a single staffing assignment by its unique ID.")
    @ApiResponse(responseCode = "200", description = "Assignment found.")
    @ApiResponse(responseCode = "404", description = "Assignment not found.")
    @GetMapping("/{assignmentId}")
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
            summary = "Update staffing assignment",
            description = "Updates an existing staffing assignment, including role, dates, and primary flag.")
    @ApiResponse(responseCode = "200", description = "Assignment updated.")
    @ApiResponse(responseCode = "400", description = "Validation error.")
    @ApiResponse(responseCode = "404", description = "Assignment not found.")
    @ApiResponse(responseCode = "409", description = "Overlapping assignment exists.")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_UPDATE", apiVersion = "1")
    @PutMapping("/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('people:employee:edit')")
    public ResponseEntity<StaffingAssignmentResponse> updateAssignment(
            @PathVariable @NonNull UUID assignmentId,
            @Valid @RequestBody @NonNull UpdateStaffingAssignmentRequest request,
            @AuthenticationPrincipal String actor) {
        return staffingAssignmentService
                .update(assignmentId, request, actor != null ? actor : "system")
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @Operation(
            summary = "End (soft-delete) an assignment",
            description = "Ends an active staffing assignment without physically deleting the record.")
    @ApiResponse(responseCode = "204", description = "Assignment ended.")
    @ApiResponse(responseCode = "404", description = "Assignment not found.")
    @EmitEvent(id = "PEOPLE_STAFFING_ASSIGNMENT_END", apiVersion = "1")
    @DeleteMapping("/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people:employee:edit"})
    @PreAuthorize("hasAuthority('people:employee:edit')")
    public ResponseEntity<Void> endAssignment(@PathVariable @NonNull UUID assignmentId) {
        staffingAssignmentService.end(assignmentId);
        return ResponseEntity.noContent().build();
    }
}

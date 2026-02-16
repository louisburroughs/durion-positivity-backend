package com.positivity.people.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.people.internal.client.dto.Role;
import com.positivity.people.internal.client.dto.RoleAssignment;
import com.positivity.people.internal.client.dto.RoleAssignmentRequest;
import com.positivity.people.service.PeopleAccessControlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/people/{personId}/access")
@Tag(name = "People Access API", description = "Person-centric access control facade for roles and assignments")
public class PersonAccessController {

    private final PeopleAccessControlService peopleAccessControlService;

    public PersonAccessController(@NonNull PeopleAccessControlService peopleAccessControlService) {
        this.peopleAccessControlService = peopleAccessControlService;
    }

    @GetMapping("/roles")
    @EmitEvent(id = "PEOPLE_ACCESS_ROLES_LIST", apiVersion = "1")
    @Operation(summary = "List available roles", description = "Returns all available roles that can be assigned")
    @ApiResponse(responseCode = "200", description = "Roles returned successfully", content = @Content(schema = @Schema(implementation = Role.class)))
    public ResponseEntity<List<Role>> getRoles(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId) {
        return ResponseEntity.ok(peopleAccessControlService.getRolesForPerson());
    }

    @GetMapping("/assignments")
    @EmitEvent(id = "PEOPLE_ACCESS_ASSIGNMENTS_LIST", apiVersion = "1")
    @Operation(summary = "Get role assignments for person", description = "Returns role assignments for the person, optionally including history")
    @ApiResponse(responseCode = "200", description = "Assignments returned successfully", content = @Content(schema = @Schema(implementation = RoleAssignment.class)))
    @ApiResponse(responseCode = "404", description = "Person-user link not found")
    public ResponseEntity<List<RoleAssignment>> getAssignments(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId,
            @Parameter(description = "Include historical assignments", example = "false") @RequestParam(defaultValue = "false") boolean includeHistory) {
        return ResponseEntity.ok(peopleAccessControlService.getAssignmentsForPerson(personId, includeHistory));
    }

    @PostMapping("/assignments")
    @EmitEvent(id = "PEOPLE_ACCESS_ASSIGNMENT_CREATE", apiVersion = "1")
    @Operation(summary = "Create role assignment for person", description = "Creates a new role assignment for the person")
    @ApiResponse(responseCode = "201", description = "Assignment created successfully", content = @Content(schema = @Schema(implementation = RoleAssignment.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "404", description = "Person-user link not found")
    public ResponseEntity<RoleAssignment> createAssignment(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId,
            @Valid @RequestBody RoleAssignmentRequest request) {
        RoleAssignment created = peopleAccessControlService.createAssignmentForPerson(personId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @EmitEvent(id = "PEOPLE_ACCESS_ASSIGNMENT_REVOKE", apiVersion = "1")
    @Operation(summary = "Revoke role assignment", description = "Revokes an assignment by setting end date; defaults to current date if omitted")
    @ApiResponse(responseCode = "204", description = "Assignment revoked")
    @ApiResponse(responseCode = "404", description = "Assignment not found")
    public ResponseEntity<Void> revokeAssignment(
            @Parameter(description = "Person ID", required = true) @PathVariable UUID personId,
            @Parameter(description = "Assignment ID", required = true) @PathVariable UUID assignmentId,
            @Parameter(description = "Effective end date", example = "2026-02-16") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        LocalDate effectiveEndDate = endDate != null ? endDate : LocalDate.now();
        peopleAccessControlService.revokeAssignmentForPerson(assignmentId, effectiveEndDate);
        return ResponseEntity.noContent().build();
    }
}
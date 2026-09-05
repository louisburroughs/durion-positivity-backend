package com.positivity.peoplecontact.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.peoplecontact.internal.client.dto.RoleDto;
import com.positivity.peoplecontact.internal.client.dto.UserRoleDto;
import com.positivity.peoplecontact.internal.dto.PersonRoleAssignmentRequest;
import com.positivity.peoplecontact.internal.exception.PeopleContactValidationException;
import com.positivity.peoplecontact.internal.security.PeopleContactPermissions;
import com.positivity.peoplecontact.internal.service.PeopleAccessControlService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/people")
@Tag(
        name = "People - Access Control",
        description = "APIs for managing person-to-role assignments for access control and permissions (CAP-120)")
public class PersonAccessController {

    private final PeopleAccessControlService peopleAccessControlService;

    @GetMapping("/{personUuid}/access/roles")
    @EmitEvent(id = "PEOPLE_CONTACT_ACCESS_ROLES_LIST", apiVersion = "1")
    @Operation(operationId = "listAssignableRoles", summary = "List Roles Assignable to Person", description = """
                    Lists the role catalog a person could be assigned, combining LOCATION-scoped and \
                    GLOBAL-scoped roles fetched live from pos-security.
                    Use this tool to populate a role picker before calling assignRoleToPerson; do not use \
                    listRoleAssignments, which returns the roles the person already holds.
                    Preconditions: the person record must exist; the roles themselves are owned by pos-security, \
                    and no user-person link is needed for this listing.
                    Required inputs: personUuid (UUID) as a path parameter; there is no request body and no \
                    filtering.
                    Emits a PEOPLE_CONTACT_ACCESS_ROLES_LIST audit event; no state changes.
                    Returns 404 when the person does not exist, and propagates the pos-security status code when \
                    the role listing call fails there.
                    """)
    @ApiResponse(responseCode = "200", description = "Roles retrieved successfully")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:role:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ROLE_VIEW + "')")
    public ResponseEntity<List<RoleDto>> getRoles(@PathVariable UUID personUuid) {
        return ResponseEntity.ok(peopleAccessControlService.getAvailableRolesForPerson(personUuid));
    }

    @GetMapping("/{personUuid}/access/assignments")
    @EmitEvent(id = "PEOPLE_CONTACT_ACCESS_ASSIGNMENTS_LIST", apiVersion = "1")
    @Operation(operationId = "listRoleAssignments", summary = "List a Person's Role Assignments", description = """
                    Lists the role assignments a person holds in pos-security, resolved through the person's \
                    active user-person link.
                    Use this tool to inspect the access a person already has; do not use listAssignableRoles, \
                    which returns the catalog of roles available for assignment.
                    Preconditions: the person must have an active user-person link, and the linked username must \
                    resolve to a pos-security user.
                    Required inputs: personUuid (UUID) as a path parameter; includeHistory defaults to false and \
                    adds ended assignments when true, and endDate (ISO date-time) optionally evaluates \
                    assignments as of that moment.
                    Emits a PEOPLE_CONTACT_ACCESS_ASSIGNMENTS_LIST audit event; no state changes.
                    Returns 404 when the person has no user link or the linked username has no security user.
                    """)
    @ApiResponse(responseCode = "200", description = "Assignments retrieved successfully")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:role:view"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ROLE_VIEW + "')")
    public ResponseEntity<List<UserRoleDto>> getAssignments(
            @PathVariable UUID personUuid,
            @RequestParam(required = false) Boolean includeHistory,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        return ResponseEntity.ok(peopleAccessControlService.getPersonRoleAssignments(
                personUuid, Boolean.TRUE.equals(includeHistory), endDate));
    }

    @PostMapping("/{personUuid}/access/assignments")
    @EmitEvent(id = "PEOPLE_CONTACT_ACCESS_ASSIGNMENT_CREATE", apiVersion = "1")
    @Operation(operationId = "assignRoleToPerson", summary = "Assign a Role to a Person", description = """
                    Assigns a role to a person by delegating to pos-security through the person's linked user \
                    account, optionally scoped to one location and bounded by a date window.
                    Use this tool to grant access; do not use revokePersonRoleAssignment, which ends an assignment \
                    that already exists.
                    Preconditions: the person must have an active user-person link resolving to a pos-security \
                    user, and the roleCode must exist in pos-security.
                    Required inputs: personUuid (UUID) as a path parameter and roleCode in the body; locationId \
                    (UUID) scopes the role to one location when supplied, and optional startDate/endDate bound \
                    the assignment, with endDate required to be on or after startDate.
                    Emits a PEOPLE_CONTACT_ACCESS_ASSIGNMENT_CREATE event; the assignment record itself is \
                    created and owned by pos-security.
                    Returns 201 with the created assignment, 400 when roleCode is blank or endDate precedes \
                    startDate, and 404 when the person's user link, the security user, or the role cannot be \
                    resolved.
                    """)
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "201",
                        description = "Role assignment created successfully",
                        content = @Content(schema = @Schema(implementation = UserRoleDto.class))),
                @ApiResponse(
                        responseCode = "400",
                        description = "Invalid request",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class))),
                @ApiResponse(
                        responseCode = "404",
                        description = "Person or role not found",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = ApiError.class)))
            })
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:role:assign"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ROLE_ASSIGN + "')")
    public ResponseEntity<UserRoleDto> createAssignment(
            @PathVariable UUID personUuid,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Role code plus optional location scope and effective date window.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Location-scoped technician role",
                                                            value = """
                                                                    {"roleCode":"TECHNICIAN",
                                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                     "startDate":"2026-09-01T00:00:00",
                                                                     "endDate":"2026-12-31T23:59:59"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    PersonRoleAssignmentRequest request) {
        if (request.getRoleCode() == null || request.getRoleCode().isBlank()) {
            throw new PeopleContactValidationException("roleCode is required");
        }
        UserRoleDto created = peopleAccessControlService.assignRoleToPerson(
                personUuid,
                request.getRoleCode(),
                request.getLocationId(),
                request.getStartDate(),
                request.getEndDate());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/{personUuid}/access/assignments/{roleCode}")
    @EmitEvent(id = "PEOPLE_CONTACT_ACCESS_ASSIGNMENT_REVOKE", apiVersion = "1")
    @Operation(
            operationId = "revokePersonRoleAssignment",
            summary = "Revoke Role Assignment from Person",
            description = """
                    Revokes one of a person's role assignments by delegating to pos-security through the \
                    person's linked user account.
                    Use this tool to end access granted with assignRoleToPerson; do not use \
                    unlinkUserFromPerson, which severs the whole user-person link rather than a single role.
                    Preconditions: the person must have an active user-person link resolving to a pos-security \
                    user, and an assignment for the roleCode must exist there.
                    Required inputs: personUuid (UUID) and roleCode as path parameters; endDate (ISO date-time) \
                    optionally ends the assignment at that moment instead of immediately.
                    Emits a PEOPLE_CONTACT_ACCESS_ASSIGNMENT_REVOKE event; the assignment state change is \
                    recorded in pos-security.
                    Returns 204 on success, and 404 when the person has no user link, the security user cannot \
                    be resolved, or pos-security has no matching assignment.
                    """)
    @ApiResponse(responseCode = "204", description = "Role assignment revoked successfully")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request for revoking role assignment",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Person or role assignment not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"people-contact:role:revoke"})
    @PreAuthorize("hasAuthority('" + PeopleContactPermissions.ROLE_REVOKE + "')")
    public ResponseEntity<Void> revokeAssignment(
            @PathVariable UUID personUuid,
            @PathVariable String roleCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        peopleAccessControlService.revokeRoleFromPerson(personUuid, roleCode, endDate);
        return ResponseEntity.noContent().build();
    }
}

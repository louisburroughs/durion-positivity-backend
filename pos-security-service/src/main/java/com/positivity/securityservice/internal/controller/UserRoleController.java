package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.service.RoleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for user-role assignment endpoints.
 * Manages assigning/revoking roles to/from users and querying effective
 * permissions.
 * Separated from RoleController to avoid path ambiguity with UserController at
 * /v1/users.
 */
@RestController
@RequestMapping("/v1/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Role Management", description = "Assign and revoke roles for users")
public class UserRoleController {

    private final RoleManagementService roleManagementService;

    /**
     * Story #62 (CAP-141): Assign a role to a user.
     */
    @EmitEvent(id = "SECURITY_USER_ROLE_ASSIGN", apiVersion = "1")
    @PutMapping("/{userId}/roles/{roleId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('security:role:assign')")
    @Operation(operationId = "assignUserRole", summary = "Assign a Role to a User", description = """
                    Creates a GLOBAL-scoped role assignment linking a user to a role, effective immediately with no \
                    end date.
                    Use this tool for the common unscoped grant; do not use createRoleAssignment, which supports \
                    LOCATION scope and effective date windows, and do not use assignPrincipalRole, which targets \
                    the string-keyed RBAC principal matrix.
                    Preconditions: the caller must hold security:role:assign and both the user and role must exist; \
                    no overlap check is performed here, so repeated calls create duplicate assignments.
                    Required inputs: userId and roleId (UUIDs) as path parameters; there is no request body.
                    Emits a SECURITY_USER_ROLE_ASSIGN event and writes a RoleAssignedToUser audit record.
                    Returns 404 when the user or role does not exist.
                    """)
    public ResponseEntity<Void> assignRoleToUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        roleManagementService.assignRoleToUser(userId, roleId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * Story #62 (CAP-141): Revoke a role from a user.
     */
    @EmitEvent(id = "SECURITY_USER_ROLE_REVOKE", apiVersion = "1")
    @DeleteMapping("/{userId}/roles/{roleId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('security:role:assign')")
    @Operation(operationId = "revokeUserRole", summary = "Revoke a Role From a User", description = """
                    Ends the first currently effective assignment of a role for a user by setting its end date to \
                    now, preserving the row for history.
                    Use this tool for the common immediate revocation; do not use revokeRoleAssignment, which \
                    targets a specific assignment id and supports past or future end dates.
                    Preconditions: the caller must hold security:role:assign, the user and role must exist, and at \
                    least one effective assignment must link them.
                    Required inputs: userId and roleId (UUIDs) as path parameters; there is no request body.
                    Emits a SECURITY_USER_ROLE_REVOKE event and writes a RoleRevokedFromUser audit record.
                    Returns 404 when the user or role does not exist, or when no active assignment links them.
                    """)
    public ResponseEntity<Void> revokeRoleFromUser(@PathVariable UUID userId, @PathVariable UUID roleId) {
        roleManagementService.revokeRoleFromUser(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62 (CAP-141): Get all effective permissions for a user.
     */
    @GetMapping("/{userId}/permissions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('security:permission:view')")
    @Operation(operationId = "getUserPermissions", summary = "Get a User's Effective Permissions", description = """
                    Returns the union of permissions granted through a user's currently effective role assignments.
                    Use this tool for a user's flattened effective permission set; use listUserRoleAssignments \
                    instead to see the assignments and scopes behind it.
                    Preconditions: the caller must hold security:permission:view and the user must exist.
                    Required inputs: userId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when the user does not exist.
                    """)
    public ResponseEntity<Set<PermissionDto>> getUserPermissions(@PathVariable UUID userId) {
        return ResponseEntity.ok(roleManagementService.getUserPermissions(userId));
    }
}

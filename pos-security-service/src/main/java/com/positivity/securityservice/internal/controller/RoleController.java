package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionGrantRequest;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.service.RoleManagementService;
import com.positivity.securityservice.service.RolePermissionService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for role management and role assignments.
 * Provides endpoints for creating roles, assigning permissions, and managing
 * user role assignments.
 */
@RestController
@RequestMapping({"/v1/roles", "/v1/users/roles"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Role Management", description = "Manage roles, permissions, and user assignments")
public class RoleController {
    private final Clock clock;

    private final RoleManagementService roleManagementService;
    private final RolePermissionService rolePermissionService;

    /**
     * Create a new role
     */
    @EmitEvent(id = "SECURITY_ROLE_CREATE", apiVersion = "1")
    @PostMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:create"})
    @PreAuthorize("hasAuthority('security:role:create')")
    @Operation(
            summary = "Create a new role",
            description = "Creates a new role with the specified name and description")
    @ApiResponse(responseCode = "201", description = "Role created")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid role payload",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Role name already exists",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> createRole(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Role name is required and cannot be blank");
        }

        RoleDto role = roleManagementService.createRole(name, description);
        return ResponseEntity.status(HttpStatus.CREATED).body(role);
    }

    /**
     * Issue #42: Grant permission to role and emit audit event.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_GRANT", apiVersion = "1")
    @PutMapping("/{roleId}/permissions/grant")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('security:role:edit')")
    @Operation(
            summary = "Grant permission to a role",
            description = "Grants a single permission to the specified role and returns the updated role")
    @ApiResponse(responseCode = "200", description = "Permission granted to role")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid grant request",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> grantPermissionToRole(
            @NonNull @PathVariable UUID roleId, @RequestBody RolePermissionGrantRequest request) {
        if (request == null
                || request.getPermission() == null
                || request.getPermission().isBlank()) {
            throw new IllegalArgumentException("permission is required");
        }
        return ResponseEntity.ok(rolePermissionService.grantPermission(roleId, request.getPermission()));
    }

    /**
     * Issue #42: Revoke permission from role.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_REVOKE", apiVersion = "1")
    @DeleteMapping("/{roleId}/permissions/{permissionKey}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('security:role:edit')")
    @Operation(
            summary = "Revoke permission from a role",
            description = "Removes the specified permission from the role")
    @ApiResponse(responseCode = "204", description = "Permission revoked from role")
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> revokePermissionFromRole(
            @NonNull @PathVariable UUID roleId, @PathVariable String permissionKey) {
        roleManagementService.revokePermissionFromRole(roleId, permissionKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62: Assign a permission to a role by permission key.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_ASSIGN", apiVersion = "1")
    @PutMapping("/{roleId}/permissions/{permissionKey}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('security:role:edit')")
    @Operation(
            summary = "Assign permission to a role by key",
            description = "Assigns the permission identified by path key to the specified role")
    @ApiResponse(responseCode = "204", description = "Permission assigned to role")
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> assignPermissionToRole(
            @NonNull @PathVariable UUID roleId, @PathVariable String permissionKey) {
        roleManagementService.assignPermissionToRole(roleId, permissionKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update permissions for a role
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSIONS_UPDATE", apiVersion = "1")
    @PutMapping("/permissions")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:edit"})
    @PreAuthorize("hasAuthority('security:role:edit')")
    @Operation(summary = "Update role permissions", description = "Assigns a set of permissions to a role")
    @ApiResponse(responseCode = "200", description = "Role permissions updated")
    @ApiResponse(
            responseCode = "404",
            description = "Role or permission not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> updateRolePermissions(@RequestBody RolePermissionsRequest request) {
        RoleDto role = roleManagementService.updateRolePermissions(request);
        return ResponseEntity.ok(role);
    }

    /**
     * Create a role assignment for a user
     */
    @EmitEvent(id = "SECURITY_ROLE_ASSIGNMENT_CREATE", apiVersion = "1")
    @PostMapping("/assignments")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('security:role:assign')")
    @Operation(
            summary = "Create role assignment",
            description = "Assigns a role to a user with optional scope and effective dates")
    @ApiResponse(responseCode = "201", description = "Role assignment created")
    @ApiResponse(
            responseCode = "404",
            description = "User or role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleAssignmentDto> createRoleAssignment(@RequestBody RoleAssignmentRequest request) {
        RoleAssignmentDto assignment = roleManagementService.createRoleAssignment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
    }

    /**
     * Get effective role assignments for a user
     */
    @GetMapping("/assignments/user/{userId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('security:role:view')")
    @Operation(
            summary = "Get user role assignments",
            description =
                    "Returns currently effective assignments by default. Set includeHistory=true to return all assignments including expired/revoked")
    @ApiResponse(responseCode = "200", description = "Role assignments returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<RoleAssignmentDto>> getUserRoleAssignments(
            @Parameter(description = "User ID", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable
                    UUID userId,
            @Parameter(description = "Include historical assignments (expired/revoked)", example = "false")
                    @RequestParam(defaultValue = "false")
                    boolean includeHistory) {
        List<RoleAssignmentDto> assignments = roleManagementService.getAssignmentsForUser(userId, includeHistory);
        return ResponseEntity.ok(assignments);
    }

    /**
     * Get all permissions for a user
     */
    @GetMapping("/permissions/user/{userId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('security:permission:view')")
    @Operation(
            summary = "Get user permissions (legacy path)",
            description = "Returns all permissions for a user from their role assignments")
    @ApiResponse(responseCode = "200", description = "User permissions returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Set<PermissionDto>> getUserPermissionsByUserId(@PathVariable UUID userId) {
        Set<PermissionDto> permissions = roleManagementService.getUserPermissions(userId);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Check if a user has a specific permission
     */
    @GetMapping("/check-permission")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:permission:view"})
    @PreAuthorize("hasAuthority('security:permission:view')")
    @Operation(
            summary = "Check user permission",
            description = "Checks if a user has a specific permission for a location")
    @ApiResponse(responseCode = "200", description = "Permission check completed")
    @ApiResponse(
            responseCode = "404",
            description = "User not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Boolean> checkUserPermission(
            @RequestParam UUID userId,
            @RequestParam String permission,
            @RequestParam(required = false) String locationId) {

        boolean hasPermission =
                roleManagementService.userHasPermission(userId, permission, locationId != null ? locationId : "GLOBAL");
        return ResponseEntity.ok(hasPermission);
    }

    /**
     * Revoke a role assignment
     */
    @EmitEvent(id = "SECURITY_ROLE_ASSIGNMENT_REVOKE", apiVersion = "1")
    @DeleteMapping("/assignments/{assignmentId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:assign"})
    @PreAuthorize("hasAuthority('security:role:assign')")
    @Operation(
            summary = "Revoke role assignment",
            description =
                    "Revokes a role assignment by setting its end date. Supports past, present, or future dates. The system automatically records when the revocation was requested. Defaults to today when endDate is omitted")
    @ApiResponse(responseCode = "204", description = "Role assignment revoked")
    @ApiResponse(responseCode = "400", description = "Invalid endDate")
    @ApiResponse(responseCode = "404", description = "Role assignment not found")
    public ResponseEntity<Void> revokeRoleAssignment(
            @Parameter(description = "Role assignment ID", example = "123e4567-e89b-12d3-a456-426614174000")
                    @PathVariable
                    UUID assignmentId,
            @Parameter(
                            description =
                                    "Effective end date and time for revocation. Defaults to current date and time",
                            example = "2026-02-16T10:30:00")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    LocalDateTime endDate) {
        LocalDateTime effectiveEndDate = endDate != null ? endDate : LocalDateTime.now(clock);
        roleManagementService.revokeRoleAssignment(assignmentId, effectiveEndDate);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all roles
     */
    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('security:role:view')")
    @Operation(summary = "Get all roles", description = "Returns all roles in the system")
    @ApiResponse(responseCode = "200", description = "Roles returned successfully")
    public ResponseEntity<List<RoleDto>> getAllRoles() {
        return ResponseEntity.ok(roleManagementService.getAllRoles());
    }

    /**
     * Get role by name.
     */
    @GetMapping("/by-name/{name}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('security:role:view')")
    @Operation(summary = "Get role by name", description = "Returns a specific role by its name")
    @ApiResponse(responseCode = "200", description = "Role returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> getRoleByName(@PathVariable String name) {
        return ResponseEntity.ok(roleManagementService.getRoleByName(name));
    }

    /**
     * Story #62: Get role by UUID.
     */
    @GetMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:view"})
    @PreAuthorize("hasAuthority('security:role:view')")
    @Operation(summary = "Get role by ID", description = "Returns a specific role by its UUID")
    @ApiResponse(responseCode = "200", description = "Role returned successfully")
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<RoleDto> getRoleById(@PathVariable UUID id) {
        return roleManagementService
                .getRoleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Story #62: Delete a role and cascade-remove all associations.
     */
    @EmitEvent(id = "SECURITY_ROLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{id}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"security:role:delete"})
    @PreAuthorize("hasAuthority('security:role:delete')")
    @Operation(summary = "Delete a role", description = "Deletes a role by UUID and removes its associations")
    @ApiResponse(responseCode = "204", description = "Role deleted")
    @ApiResponse(
            responseCode = "404",
            description = "Role not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        roleManagementService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }
}

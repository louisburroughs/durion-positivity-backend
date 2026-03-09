package com.positivity.securityservice.internal.controller;

import java.time.Clock;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionGrantRequest;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.service.RoleManagementService;
import com.positivity.securityservice.service.RolePermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * REST controller for role management and role assignments.
 * Provides endpoints for creating roles, assigning permissions, and managing
 * user role assignments.
 */
@RestController
@RequestMapping({ "/v1/roles", "/v1/users/roles", "/v1/users" })
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
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new role", description = "Creates a new role with the specified name and description")
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
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Grant permission to a role")
    public ResponseEntity<RoleDto> grantPermissionToRole(
            @PathVariable UUID roleId,
            @RequestBody RolePermissionGrantRequest request) {
        if (request == null || request.getPermission() == null || request.getPermission().isBlank()) {
            throw new IllegalArgumentException("permission is required");
        }
        return ResponseEntity.ok(rolePermissionService.grantPermission(roleId, request.getPermission()));
    }

    /**
     * Issue #42: Revoke permission from role.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_REVOKE", apiVersion = "1")
    @DeleteMapping("/{roleId}/permissions/{permissionKey}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke permission from a role")
    public ResponseEntity<Void> revokePermissionFromRole(
            @PathVariable UUID roleId,
            @PathVariable String permissionKey) {
        roleManagementService.revokePermissionFromRole(roleId, permissionKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62: Assign a permission to a role by permission key.
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSION_ASSIGN", apiVersion = "1")
    @PutMapping("/{roleId}/permissions/{permissionKey}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign permission to a role by key")
    public ResponseEntity<Void> assignPermissionToRole(
            @PathVariable UUID roleId,
            @PathVariable String permissionKey) {
        roleManagementService.assignPermissionToRole(roleId, permissionKey);
        return ResponseEntity.noContent().build();
    }

    /**
     * Update permissions for a role
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSIONS_UPDATE", apiVersion = "1")
    @PutMapping("/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update role permissions", description = "Assigns a set of permissions to a role")
    @ApiResponse(responseCode = "404", description = "Role or permission not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<RoleDto> updateRolePermissions(@RequestBody RolePermissionsRequest request) {
        RoleDto role = roleManagementService.updateRolePermissions(request);
        return ResponseEntity.ok(role);
    }

    /**
     * Create a role assignment for a user
     */
    @EmitEvent(id = "SECURITY_ROLE_ASSIGNMENT_CREATE", apiVersion = "1")
    @PostMapping("/assignments")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Create role assignment", description = "Assigns a role to a user with optional scope and effective dates")
    @ApiResponse(responseCode = "404", description = "User or role not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<RoleAssignmentDto> createRoleAssignment(@RequestBody RoleAssignmentRequest request) {
        RoleAssignmentDto assignment = roleManagementService.createRoleAssignment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
    }

    /**
     * Get effective role assignments for a user
     */
    @GetMapping("/assignments/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user role assignments", description = "Returns currently effective assignments by default. Set includeHistory=true to return all assignments including expired/revoked")
    @ApiResponse(responseCode = "200", description = "Role assignments returned successfully")
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<List<RoleAssignmentDto>> getUserRoleAssignments(
            @Parameter(description = "User ID", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID userId,
            @Parameter(description = "Include historical assignments (expired/revoked)", example = "false") @RequestParam(defaultValue = "false") boolean includeHistory) {
        List<RoleAssignmentDto> assignments = roleManagementService.getAssignmentsForUser(userId, includeHistory);
        return ResponseEntity.ok(assignments);
    }

    /**
     * Get all permissions for a user
     */
    @GetMapping("/permissions/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user permissions (legacy path)", description = "Returns all permissions for a user from their role assignments")
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Set<PermissionDto>> getUserPermissionsByUserId(@PathVariable UUID userId) {
        Set<PermissionDto> permissions = roleManagementService.getUserPermissions(userId);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Check if a user has a specific permission
     */
    @GetMapping("/check-permission")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Check user permission", description = "Checks if a user has a specific permission for a location")
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Boolean> checkUserPermission(
            @RequestParam UUID userId,
            @RequestParam String permission,
            @RequestParam(required = false) String locationId) {

        boolean hasPermission = roleManagementService.userHasPermission(
                userId, permission, locationId != null ? locationId : "GLOBAL");
        return ResponseEntity.ok(hasPermission);
    }

    /**
     * Revoke a role assignment
     */
    @EmitEvent(id = "SECURITY_ROLE_ASSIGNMENT_REVOKE", apiVersion = "1")
    @DeleteMapping("/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Revoke role assignment", description = "Revokes a role assignment by setting its end date. Supports past, present, or future dates. The system automatically records when the revocation was requested. Defaults to today when endDate is omitted")
    @ApiResponse(responseCode = "204", description = "Role assignment revoked")
    @ApiResponse(responseCode = "400", description = "Invalid endDate")
    @ApiResponse(responseCode = "404", description = "Role assignment not found")
    public ResponseEntity<Void> revokeRoleAssignment(
            @Parameter(description = "Role assignment ID", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID assignmentId,
            @Parameter(description = "Effective end date and time for revocation. Defaults to current date and time", example = "2026-02-16T10:30:00") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        LocalDateTime effectiveEndDate = endDate != null ? endDate : LocalDateTime.now(clock);
        roleManagementService.revokeRoleAssignment(assignmentId, effectiveEndDate);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all roles
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get all roles", description = "Returns all roles in the system")
    public ResponseEntity<List<RoleDto>> getAllRoles() {
        return ResponseEntity.ok(roleManagementService.getAllRoles());
    }

    /**
     * Story #62: Get role by UUID.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get role by ID", description = "Returns a specific role by its UUID")
    @ApiResponse(responseCode = "404", description = "Role not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<RoleDto> getRoleById(@PathVariable UUID id) {
        return roleManagementService.getRoleById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Story #62: Delete a role and cascade-remove all associations.
     */
    @EmitEvent(id = "SECURITY_ROLE_DELETE", apiVersion = "1")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a role")
    public ResponseEntity<Void> deleteRole(@PathVariable UUID id) {
        roleManagementService.deleteRole(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62: Assign a role to a user. Resolved via /v1/users base.
     */
    @EmitEvent(id = "SECURITY_USER_ROLE_ASSIGN", apiVersion = "1")
    @PutMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Assign a role to a user")
    public ResponseEntity<Void> assignRoleToUser(
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {
        roleManagementService.assignRoleToUser(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62: Revoke a role from a user. Resolved via /v1/users base.
     */
    @EmitEvent(id = "SECURITY_USER_ROLE_REVOKE", apiVersion = "1")
    @DeleteMapping("/{userId}/roles/{roleId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke a role from a user")
    public ResponseEntity<Void> revokeRoleFromUser(
            @PathVariable UUID userId,
            @PathVariable UUID roleId) {
        roleManagementService.revokeRoleFromUser(userId, roleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Story #62: Get all effective permissions for a user. Resolved via /v1/users base.
     */
    @GetMapping("/{userId}/permissions")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user permissions", description = "Returns all effective permissions for a user")
    public ResponseEntity<Set<PermissionDto>> getUserPermissions(@PathVariable UUID userId) {
        return ResponseEntity.ok(roleManagementService.getUserPermissions(userId));
    }
}

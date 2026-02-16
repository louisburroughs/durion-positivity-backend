package com.positivity.securityservice.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.securityservice.internal.dto.ErrorResponse;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import com.positivity.securityservice.service.RoleManagementService;
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
@RequestMapping("/v1/roles")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Role Management", description = "Manage roles, permissions, and user assignments")
public class RoleController {

    private final RoleManagementService roleManagementService;

    /**
     * Create a new role
     */
    @EmitEvent(id = "SECURITY_ROLE_CREATE", apiVersion = "1")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new role", description = "Creates a new role with the specified name and description")
    public ResponseEntity<Role> createRole(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");

        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Role name is required and cannot be blank");
        }

        Role role = roleManagementService.createRole(name, description);
        return ResponseEntity.status(HttpStatus.CREATED).body(role);
    }

    /**
     * Update permissions for a role
     */
    @EmitEvent(id = "SECURITY_ROLE_PERMISSIONS_UPDATE", apiVersion = "1")
    @PutMapping("/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update role permissions", description = "Assigns a set of permissions to a role")
    @ApiResponse(responseCode = "404", description = "Role or permission not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Role> updateRolePermissions(@RequestBody RolePermissionsRequest request) {
        Role role = roleManagementService.updateRolePermissions(request);
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
    public ResponseEntity<RoleAssignment> createRoleAssignment(@RequestBody RoleAssignmentRequest request) {
        RoleAssignment assignment = roleManagementService.createRoleAssignment(request);
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
    public ResponseEntity<List<RoleAssignment>> getUserRoleAssignments(
            @Parameter(description = "User ID", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID userId,
            @Parameter(description = "Include historical assignments (expired/revoked)", example = "false") @RequestParam(defaultValue = "false") boolean includeHistory) {
        List<RoleAssignment> assignments = roleManagementService.getAssignmentsForUser(userId, includeHistory);
        return ResponseEntity.ok(assignments);
    }

    /**
     * Get all permissions for a user
     */
    @GetMapping("/permissions/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user permissions", description = "Returns all permissions for a user from their role assignments")
    @ApiResponse(responseCode = "404", description = "User not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Set<Permission>> getUserPermissions(@PathVariable UUID userId) {
        Set<Permission> permissions = roleManagementService.getUserPermissions(userId);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Check if a user has a specific permission
     */
    @GetMapping("/check-permission")
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
    @Operation(summary = "Revoke role assignment", description = "Revokes a role assignment by setting its end date. Defaults to today when endDate is omitted")
    @ApiResponse(responseCode = "204", description = "Role assignment revoked")
    @ApiResponse(responseCode = "400", description = "Invalid endDate")
    @ApiResponse(responseCode = "404", description = "Role assignment not found")
    public ResponseEntity<Void> revokeRoleAssignment(
            @Parameter(description = "Role assignment ID", example = "123e4567-e89b-12d3-a456-426614174000") @PathVariable UUID assignmentId,
            @Parameter(description = "Effective end date for revocation. Defaults to current date and time", example = "2026-02-16T10:30:00") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endDate) {
        LocalDateTime effectiveEndDate = endDate != null ? endDate : LocalDateTime.now();
        roleManagementService.revokeRoleAssignment(assignmentId, effectiveEndDate);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all roles
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get all roles", description = "Returns all roles in the system")
    public ResponseEntity<List<Role>> getAllRoles() {
        return ResponseEntity.ok(roleManagementService.getAllRoles());
    }

    /**
     * Get role by name
     */
    @GetMapping("/{name}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get role by name", description = "Returns a specific role by its name")
    @ApiResponse(responseCode = "404", description = "Role not found", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<Role> getRoleByName(@PathVariable String name) {
        Role role = roleManagementService.getRoleByName(name);
        return ResponseEntity.ok(role);
    }
}

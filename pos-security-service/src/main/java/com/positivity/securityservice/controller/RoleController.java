package com.positivity.securityservice.controller;

import com.positivity.securityservice.dto.RoleAssignmentRequest;
import com.positivity.securityservice.dto.RolePermissionsRequest;
import com.positivity.securityservice.model.Permission;
import com.positivity.securityservice.model.Role;
import com.positivity.securityservice.model.RoleAssignment;
import com.positivity.securityservice.service.RoleManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for role management and role assignments.
 * Provides endpoints for creating roles, assigning permissions, and managing user role assignments.
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
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a new role",
               description = "Creates a new role with the specified name and description")
    public ResponseEntity<Role> createRole(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String description = request.get("description");
        
        if (name == null || name.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        
        try {
            Role role = roleManagementService.createRole(name, description);
            return ResponseEntity.status(HttpStatus.CREATED).body(role);
        } catch (IllegalArgumentException e) {
            log.error("Failed to create role: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update permissions for a role
     */
    @PutMapping("/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update role permissions",
               description = "Assigns a set of permissions to a role")
    public ResponseEntity<Role> updateRolePermissions(@RequestBody RolePermissionsRequest request) {
        try {
            Role role = roleManagementService.updateRolePermissions(request);
            return ResponseEntity.ok(role);
        } catch (IllegalArgumentException e) {
            log.error("Failed to update role permissions: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Create a role assignment for a user
     */
    @PostMapping("/assignments")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Create role assignment",
               description = "Assigns a role to a user with optional scope and effective dates")
    public ResponseEntity<RoleAssignment> createRoleAssignment(@RequestBody RoleAssignmentRequest request) {
        try {
            RoleAssignment assignment = roleManagementService.createRoleAssignment(request);
            return ResponseEntity.status(HttpStatus.CREATED).body(assignment);
        } catch (IllegalArgumentException e) {
            log.error("Failed to create role assignment: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get effective role assignments for a user
     */
    @GetMapping("/assignments/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user role assignments",
               description = "Returns all effective role assignments for a user")
    public ResponseEntity<List<RoleAssignment>> getUserRoleAssignments(@PathVariable Long userId) {
        try {
            List<RoleAssignment> assignments = roleManagementService.getEffectiveRoleAssignments(userId);
            return ResponseEntity.ok(assignments);
        } catch (IllegalArgumentException e) {
            log.error("Failed to get role assignments: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all permissions for a user
     */
    @GetMapping("/permissions/user/{userId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get user permissions",
               description = "Returns all permissions for a user from their role assignments")
    public ResponseEntity<Set<Permission>> getUserPermissions(@PathVariable Long userId) {
        try {
            Set<Permission> permissions = roleManagementService.getUserPermissions(userId);
            return ResponseEntity.ok(permissions);
        } catch (IllegalArgumentException e) {
            log.error("Failed to get user permissions: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Check if a user has a specific permission
     */
    @GetMapping("/check-permission")
    @Operation(summary = "Check user permission",
               description = "Checks if a user has a specific permission for a location")
    public ResponseEntity<Boolean> checkUserPermission(
            @RequestParam Long userId,
            @RequestParam String permission,
            @RequestParam(required = false) String locationId) {
        
        try {
            boolean hasPermission = roleManagementService.userHasPermission(
                userId, permission, locationId != null ? locationId : "GLOBAL");
            return ResponseEntity.ok(hasPermission);
        } catch (IllegalArgumentException e) {
            log.error("Failed to check permission: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Revoke a role assignment
     */
    @DeleteMapping("/assignments/{assignmentId}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Revoke role assignment",
               description = "Revokes a role assignment by setting its end date")
    public ResponseEntity<Void> revokeRoleAssignment(@PathVariable Long assignmentId) {
        try {
            roleManagementService.revokeRoleAssignment(assignmentId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            log.error("Failed to revoke role assignment: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Get all roles
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get all roles",
               description = "Returns all roles in the system")
    public ResponseEntity<List<Role>> getAllRoles() {
        return ResponseEntity.ok(roleManagementService.getAllRoles());
    }

    /**
     * Get role by name
     */
    @GetMapping("/{name}")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MANAGER')")
    @Operation(summary = "Get role by name",
               description = "Returns a specific role by its name")
    public ResponseEntity<Role> getRoleByName(@PathVariable String name) {
        try {
            Role role = roleManagementService.getRoleByName(name);
            return ResponseEntity.ok(role);
        } catch (IllegalArgumentException e) {
            log.error("Failed to get role: {}", e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}

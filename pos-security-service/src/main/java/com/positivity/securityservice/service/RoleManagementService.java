package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.entity.Permission;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.RoleAssignment;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Service for managing roles, role assignments, and role-permission mappings.
 * Implements the foundational RBAC framework with scope support.
 */
public interface RoleManagementService {

    /**
     * Create a new role
     */
    Role createRole(String name, String description);

    /**
     * Update permissions for a role
     */
    Role updateRolePermissions(RolePermissionsRequest request);

    /**
     * Create a role assignment for a user
     */
    RoleAssignment createRoleAssignment(RoleAssignmentRequest request);

    /**
     * Get effective role assignments for a user
     */
    List<RoleAssignment> getAssignmentsForUser(@NonNull UUID userId, boolean includeHistory);

    /**
     * Get effective role assignments for a user
     */
    List<RoleAssignment> getEffectiveRoleAssignments(@NonNull UUID userId);

    /**
     * Get all permissions for a user (from all their effective role assignments)
     */
    Set<Permission> getUserPermissions(UUID userId);

    /**
     * Check if a user has a specific permission, considering scope
     */
    boolean userHasPermission(UUID userId, String permissionName, String locationId);

    /**
     * Revoke a role assignment
     */
    void revokeRoleAssignment(@NonNull UUID assignmentId, @NonNull LocalDate endDate);

    /**
     * Get all roles
     */
    List<Role> getAllRoles();

    /**
     * Get role by name
     */
    Role getRoleByName(String name);
}

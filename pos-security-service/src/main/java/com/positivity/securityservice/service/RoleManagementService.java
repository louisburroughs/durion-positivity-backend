package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleDto;
import org.jspecify.annotations.NonNull;

import java.time.LocalDateTime;
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
    RoleDto createRole(String name, String description);

    /**
     * Update permissions for a role
     */
    RoleDto updateRolePermissions(RolePermissionsRequest request);

    /**
     * Create a role assignment for a user
     */
    RoleAssignmentDto createRoleAssignment(RoleAssignmentRequest request);

    /**
     * Get effective role assignments for a user
     */
    List<RoleAssignmentDto> getAssignmentsForUser(@NonNull UUID userId, boolean includeHistory);

    /**
     * Get effective role assignments for a user
     */
    List<RoleAssignmentDto> getEffectiveRoleAssignments(@NonNull UUID userId);

    /**
     * Get all permissions for a user (from all their effective role assignments)
     */
    Set<PermissionDto> getUserPermissions(UUID userId);

    /**
     * Check if a user has a specific permission, considering scope
     */
    boolean userHasPermission(UUID userId, String permissionName, String locationId);

    /**
     * Revoke a role assignment
     */
    void revokeRoleAssignment(@NonNull UUID assignmentId, @NonNull LocalDateTime endDate);

    /**
     * Get all roles
     */
    List<RoleDto> getAllRoles();

    /**
     * Get role by name
     */
    RoleDto getRoleByName(String name);
}

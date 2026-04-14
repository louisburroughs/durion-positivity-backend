package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentDto;
import com.positivity.securityservice.internal.dto.RoleAssignmentRequest;
import com.positivity.securityservice.internal.dto.RoleDto;
import com.positivity.securityservice.internal.dto.RolePermissionsRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service for managing roles, role assignments, and role-permission mappings.
 * Implements the foundational RBAC framework with scope support.
 */
public interface RoleManagementService {

    /**
     * Create a new role
     */
    RoleDto createRole(@NonNull String name, @Nullable String description);

    /**
     * Update permissions for a role
     */
    RoleDto updateRolePermissions(@NonNull RolePermissionsRequest request);

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

    /**
     * Get a role by its UUID (Story #62).
     *
     * @return present if found, empty if not found
     */
    Optional<RoleDto> getRoleById(@NonNull UUID id);

    /**
     * Delete a role and cascade-remove all role_permission and user_role
     * associations (Story #62).
     */
    void deleteRole(@NonNull UUID id);

    /**
     * Assign a permission to a role by permission key (Story #62).
     *
     * @throws com.positivity.securityservice.internal.exception.PermissionNotFoundException if
     *                                                                                       permissionKey
     *                                                                                       is
     *                                                                                       not
     *                                                                                       registered
     */
    void assignPermissionToRole(@NonNull UUID roleId, @NonNull String permissionKey);

    /**
     * Revoke a permission from a role (Story #62).
     */
    void revokePermissionFromRole(@NonNull UUID roleId, @NonNull String permissionKey);

    /**
     * Assign a role to a user (Story #62).
     */
    void assignRoleToUser(@NonNull UUID userId, @NonNull UUID roleId);

    /**
     * Revoke a role from a user (Story #62).
     */
    void revokeRoleFromUser(@NonNull UUID userId, @NonNull UUID roleId);
}

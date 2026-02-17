package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import com.positivity.securityservice.internal.entity.Permission;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing the central permission registry.
 * Handles permission registration, validation, and querying.
 */
public interface PermissionRegistryService {

    /**
     * Register or update permissions from a service manifest
     */
    PermissionRegistrationResponse registerPermissions(PermissionRegistrationRequest request);

    /**
     * Validate permission name format
     */
    boolean isValidPermissionName(String name);

    /**
     * Get all permissions for a domain
     */
    List<Permission> getPermissionsByDomain(String domain);

    /**
     * Get all registered permissions
     */
    List<Permission> getAllPermissions();

    /**
     * Check if a permission exists
     */
    boolean permissionExists(String permissionName);

    /**
     * Get permission by name
     */
    Optional<Permission> getPermissionByName(String name);
}

package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.PermissionDto;
import com.positivity.securityservice.internal.dto.PermissionRegistrationRequest;
import com.positivity.securityservice.internal.dto.PermissionRegistrationResponse;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * Service for managing the central permission registry.
 * Handles permission registration, validation, and querying.
 */
public interface PermissionRegistryService {

    /**
     * Register or update permissions from a service manifest
     */
    PermissionRegistrationResponse registerPermissions(@NonNull PermissionRegistrationRequest request);

    /**
     * Validate permission name format
     */
    boolean isValidPermissionName(String name);

    /**
     * Get all permissions for a domain
     */
    List<PermissionDto> getPermissionsByDomain(@NonNull String domain);

    /**
     * Get all registered permissions
     */
    List<PermissionDto> getAllPermissions();

    /**
     * Check if a permission exists
     */
    boolean permissionExists(@NonNull String permissionName);

    /**
     * Get permission by name
     */
    Optional<PermissionDto> getPermissionByName(@NonNull String name);
}

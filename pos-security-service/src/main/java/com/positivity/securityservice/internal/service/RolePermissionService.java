package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.RoleDto;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for role-permission mappings.
 *
 * Issue: #42
 */
public interface RolePermissionService {

    RoleDto createRole(@NonNull String roleName, String description);

    RoleDto grantPermission(@NonNull UUID roleId, @NonNull String permissionKey);

    RoleDto revokePermission(@NonNull UUID roleId, @NonNull String permissionKey);
}

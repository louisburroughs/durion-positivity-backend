package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.RoleDto;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for role-permission and principal-role mappings.
 *
 * Issue: #42
 */
public interface RolePermissionService {

    RoleDto createRole(@NonNull String roleName, String description);

    RoleDto grantPermission(@NonNull UUID roleId, @NonNull String permissionKey);

    RoleDto revokePermission(@NonNull UUID roleId, @NonNull String permissionKey);

    void assignRoleToPrincipal(@NonNull String principalId, @NonNull UUID roleId);
}

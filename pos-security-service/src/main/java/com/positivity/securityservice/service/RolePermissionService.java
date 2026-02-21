package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.entity.Role;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service API for role-permission and principal-role mappings.
 *
 * Issue: #42
 */
public interface RolePermissionService {

    Role createRole(@NonNull String roleName, String description);

    Role grantPermission(@NonNull UUID roleId, @NonNull String permissionKey);

    Role revokePermission(@NonNull UUID roleId, @NonNull String permissionKey);

    void assignRoleToPrincipal(@NonNull String principalId, @NonNull UUID roleId);
}

package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;

/**
 * One {@code role_permissions} row with the granting role's location reach, as projected by
 * {@code RoleRepository.findGrantRowsByRoleNames} (ADR-0061 §2, #1868). Grouped per role by
 * {@code RoleAuthorityService.resolveRoleGrants}; not an API type.
 *
 * @param roleName          the role's stored name (any case)
 * @param locationScope     the role's {@code location_scope}
 * @param locationHierarchy the role's {@code location_hierarchy}
 * @param permissionName    one permission granted to the role
 */
public record RoleGrantRow(
        String roleName, LocationScope locationScope, LocationHierarchy locationHierarchy, String permissionName) {}

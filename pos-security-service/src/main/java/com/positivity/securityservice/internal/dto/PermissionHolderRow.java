package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.LocationScope;

/**
 * One {@code role_permissions} row of a requested permission, with the granting role's name,
 * template key and location scope, as projected by
 * {@code RoleRepository.findHolderRowsByPermissionNames} (#2669). Grouped per permission by
 * {@code PermissionHolderService}; not an API type.
 *
 * @param permission    the permission code granted
 * @param roleName      the role's stored name
 * @param templateKey   the role's template key, {@code null} for a tenant's custom role
 * @param locationScope the role's {@code location_scope}
 */
public record PermissionHolderRow(
        String permission, String roleName, String templateKey, LocationScope locationScope) {}

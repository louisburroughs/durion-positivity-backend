package com.positivity.securityservice.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * Request DTO for updating role permissions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionsRequest {
    private Long roleId;
    private Set<String> permissionNames;
}

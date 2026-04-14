package com.positivity.securityservice.internal.dto;

import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for updating role permissions
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RolePermissionsRequest {
    private UUID roleId;
    private Set<String> permissionNames;
}

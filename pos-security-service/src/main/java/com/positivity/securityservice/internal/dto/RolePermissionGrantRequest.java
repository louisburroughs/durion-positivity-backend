package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

/**
 * Request payload for granting/revoking a permission key on a role.
 *
 * Issue: #42
 */
@Data
public class RolePermissionGrantRequest {

    @JsonAlias({ "permissionKey" })
    private String permission;
}

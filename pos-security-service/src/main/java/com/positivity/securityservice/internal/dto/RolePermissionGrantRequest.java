package com.positivity.securityservice.internal.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request payload for granting/revoking a permission key on a role.
 *
 * Issue: #42
 */
@Data
@Schema(description = "Request to grant or revoke a single permission on a role")
public class RolePermissionGrantRequest {

    @JsonAlias({"permissionKey"})
    @NotBlank
    @Schema(
            description = "Permission key in format domain:resource:action",
            example = "people:timekeeping:view",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String permission;
}

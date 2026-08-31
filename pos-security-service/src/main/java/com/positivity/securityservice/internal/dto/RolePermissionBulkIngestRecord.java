package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.Set;

/**
 * Permission grants for one existing role (#1613, D8 constraint 2).
 *
 * <p>Separate from role creation because permissions are registered code-first by each module at
 * startup: the set a role can be granted is not knowable when the role is created. Running this load
 * after the platform is up is also a correctness gain over the SQL seed, which ran during
 * pos-security-service boot — before the other modules had registered anything.
 */
@Schema(description = "Permission grants for one existing role")
public record RolePermissionBulkIngestRecord(
        @Schema(description = "Role to grant to; must already exist", example = "SHOP_MANAGER")
        @NotBlank(message = "roleName is required")
        String roleName,

        @Schema(description = "Permission names to grant", example = "[\"crm:party:view\"]")
        @NotEmpty(message = "at least one permission is required")
        Set<@NotBlank String> permissions) {}

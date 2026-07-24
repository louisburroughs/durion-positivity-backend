package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Default authority codes a role expands to (#782). Consumed by pos-mcp-server to prebuild per-role
 * agent tool caches with the role's real permission set instead of an AUTHENTICATED-only warm-up.
 */
@Schema(description = "Default authority codes granted by a role")
public record RoleDefaultPermissionsResponse(
        @Schema(
                description = "Role name as requested (with or without the ROLE_ prefix)",
                example = "MANAGER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String role,

        @Schema(
                description = "Sorted authority codes the role expands to (ROLE_* plus domain permission codes)",
                example = "[\"ROLE_MANAGER\", \"crm:party:view\"]",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> permissions) {}

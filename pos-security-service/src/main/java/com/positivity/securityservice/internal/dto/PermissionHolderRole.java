package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.LocationScope;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A role of the caller's tenant that holds a requested permission (#2669). Carries no user data:
 * roles are the only path to a permission, so they answer the policy question completely.
 *
 * @param name          the role's name as stored
 * @param templateKey   the canonical template name, or {@code null} for a custom role
 * @param locationScope the role's location reach
 */
@Schema(description = "A role of the caller's tenant that holds the permission, as currently configured")
public record PermissionHolderRole(
        @Schema(
                description = "The role's name as the tenant stores it. For a custom role this is tenant-authored"
                        + " text; show it as typed.",
                example = "CONTROLLER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(
                description = "The canonical template name when the role was provisioned from the platform role"
                        + " template, for the client to translate; null for a tenant's custom role.",
                example = "CONTROLLER",
                nullable = true,
                requiredMode = Schema.RequiredMode.REQUIRED)
        String templateKey,

        @Schema(
                description = "ALL when the role holds its permissions at every location; LOCATION when it holds"
                        + " them only at the locations its holder is assigned to (ADR-0061 §2).",
                example = "ALL",
                requiredMode = Schema.RequiredMode.REQUIRED)
        LocationScope locationScope) {}

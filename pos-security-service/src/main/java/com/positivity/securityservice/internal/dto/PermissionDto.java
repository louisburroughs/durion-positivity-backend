package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Permission DTO used by user-facing RBAC endpoints.
 *
 * Issue: #42
 */
@Value
@Builder
@Schema(description = "A permission entry exposed by user-facing RBAC endpoints")
public class PermissionDto {
    @Schema(
            description = "Permission identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID id;

    @Schema(
            description = "Permission name in format domain:resource:action",
            example = "people:timekeeping:view",
            requiredMode = REQUIRED)
    String name;

    @Schema(description = "Domain that owns the permission", example = "people", requiredMode = REQUIRED)
    String domain;

    @Schema(
            description = "Human-readable description of the permission",
            example = "View timekeeping records",
            requiredMode = NOT_REQUIRED)
    String description;

    @Schema(description = "True when the permission is deprecated", example = "false", requiredMode = REQUIRED)
    boolean deprecated;
}

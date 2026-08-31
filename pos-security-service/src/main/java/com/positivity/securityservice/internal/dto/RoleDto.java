package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Role definition including its granted permissions and audit metadata")
public class RoleDto {
    @Schema(description = "Role identifier", example = "01960003-0000-7000-8000-000000000010", requiredMode = REQUIRED)
    UUID id;

    @Schema(description = "Role name", example = "SHOP_MGR", requiredMode = REQUIRED)
    String name;

    @Schema(
            description = "Human-readable description of the role",
            example = "Shop manager",
            requiredMode = NOT_REQUIRED)
    String description;

    @Schema(description = "Permissions granted to the role", requiredMode = NOT_REQUIRED)
    Set<PermissionDto> permissions;

    @Schema(
            description = "MCP persona slot: who the caller is, in the second person (#1613)",
            example = "shop manager",
            requiredMode = NOT_REQUIRED)
    String personaTitle;

    @Schema(
            description = "MCP persona slot: what the caller works on",
            example = "branch operations, queue control, scheduling trade-offs, and execution oversight",
            requiredMode = NOT_REQUIRED)
    String personaFocus;

    @Schema(
            description = "MCP persona slot: how to speak to the caller",
            example = "decisive, operational, and management-ready",
            requiredMode = NOT_REQUIRED)
    String personaTone;

    @Schema(
            description = "MCP persona resolution priority, lowest first; null leaves the role unranked",
            example = "35",
            requiredMode = NOT_REQUIRED)
    Short mcpPersonaRank;

    @Schema(
            description = "Whether the role participates in MCP persona resolution",
            example = "true",
            requiredMode = NOT_REQUIRED)
    boolean mcpPersonaEligible;

    @Schema(description = "Creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    Instant createdAt;

    @Schema(description = "Actor that created the role", example = "system", requiredMode = NOT_REQUIRED)
    String createdBy;

    @Schema(description = "Last-modified timestamp", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    Instant lastModifiedAt;

    @Schema(description = "Actor that last modified the role", example = "jane.doe", requiredMode = NOT_REQUIRED)
    String lastModifiedBy;
}

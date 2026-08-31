package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.validation.PersonaText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * One role to provision in bulk, with its MCP persona metadata (#1613, D8).
 *
 * <p>Grants are absent by design: they arrive through the separate
 * {@link RolePermissionBulkIngestRecord} load, because permissions are registered code-first by each
 * module at startup and so are not all present when roles are created.
 *
 * <p>Persona slots carry the same {@link PersonaText} containment as the single-role API. A
 * bulk-load file is a reviewed path, but "reviewed" is not a reason to relax the control that keeps
 * a persona filling a slot rather than becoming the prompt.
 */
@Schema(description = "One role to provision, with optional MCP persona metadata")
public record RoleBulkIngestRecord(
        @Schema(description = "Role name, unprefixed and upper-case", example = "SHOP_MANAGER")
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name must be at most 255 characters")
        String name,

        @Schema(description = "Human-readable description", example = "Branch operations lead")
        @Size(max = 500, message = "description must be at most 500 characters")
        String description,

        @Schema(description = "Persona slot: who the caller is", example = "shop manager") @PersonaText(max = 60)
        String personaTitle,

        @Schema(
                description = "Persona slot: what they work on",
                example = "branch operations, queue control, and scheduling trade-offs")
        @PersonaText(max = 200)
        String personaFocus,

        @Schema(description = "Persona slot: how to speak to them", example = "decisive and operational")
        @PersonaText(max = 120)
        String personaTone,

        @Schema(description = "Resolution priority, lowest first; null leaves the role unranked", example = "35")
        Short mcpPersonaRank,

        @Schema(description = "Whether the role participates in persona resolution; defaults to true", example = "true")
        Boolean mcpPersonaEligible) {}

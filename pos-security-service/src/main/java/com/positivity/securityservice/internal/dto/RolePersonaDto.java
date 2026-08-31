package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One role's MCP persona metadata (#1613).
 *
 * <p>A deliberately cheap projection: {@code pos-mcp-server} syncs every role on a schedule, and
 * {@link RoleDto} would drag the eagerly-fetched permission graph along with each row.
 *
 * <p>Names are unprefixed, matching the canonical storage form. Applying the {@code ROLE_} authority
 * prefix is the consumer's job, in exactly one place (D3).
 */
@Schema(description = "MCP persona metadata for a single role")
public record RolePersonaDto(
        @Schema(
                description = "Role name, unprefixed",
                example = "SHOP_MANAGER",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Schema(
                description =
                        "Human-readable description, used to derive the persona focus when personaFocus is" + " absent",
                example = "Branch operations lead",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String description,

        @Schema(
                description = "Who the caller is, in the second person",
                example = "shop manager",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String personaTitle,

        @Schema(
                description = "What the caller works on",
                example = "branch operations, queue control, scheduling trade-offs, and execution oversight",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String personaFocus,

        @Schema(
                description = "How to speak to the caller",
                example = "decisive, operational, and management-ready",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String personaTone,

        @Schema(
                description = "Resolution priority, lowest first. Null sorts after every ranked role but ahead of the"
                        + " consumer's own fallback identity.",
                example = "35",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Short mcpPersonaRank,

        @Schema(
                description = "Whether this role participates in persona resolution at all",
                example = "true",
                requiredMode = Schema.RequiredMode.REQUIRED)
        boolean mcpPersonaEligible) {}

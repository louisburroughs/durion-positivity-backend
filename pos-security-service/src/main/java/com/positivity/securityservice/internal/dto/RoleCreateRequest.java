package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.validation.PersonaText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Role creation payload (#1613). Replaces the untyped {@code Map<String, String>} body, which
 * carried no validation at all and could not express the persona slots.
 *
 * <p>Persona slots are optional: omitted slots are derived by {@code pos-mcp-server} from the role
 * name and description (D5), so a role is never required to carry curated fields.
 */
@Schema(description = "Name, description, and optional MCP persona metadata for a new role")
public record RoleCreateRequest(
        @Schema(
                description = "Role name. Stored unprefixed and upper-case; the ROLE_ authority prefix is applied"
                        + " by the API gateway, not here.",
                example = "WARRANTY_CLERK",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "Role name is required and cannot be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String name,

        @Schema(
                description = "Human-readable description of the role. Doubles as the derived persona focus when"
                        + " personaFocus is omitted.",
                example = "Warranty claim intake and settlement",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @Schema(
                description = "Who the caller is, in the second person. Derived from the role name when omitted.",
                example = "warranty clerk",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PersonaText(max = 60)
        String personaTitle,

        @Schema(
                description = "What the caller works on. Derived from the description when omitted.",
                example = "warranty claim intake, eligibility, and settlement",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PersonaText(max = 200)
        String personaFocus,

        @Schema(
                description = "How to speak to the caller. Defaults to a neutral tone when omitted.",
                example = "precise, policy-aware, and explicit about claim status",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PersonaText(max = 120)
        String personaTone,

        @Schema(
                description = "MCP persona resolution priority, lowest first. Null leaves the role unranked, which"
                        + " sorts after every ranked role but still ahead of the ROLE_USER fallback.",
                example = "45",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Short mcpPersonaRank,

        @Schema(
                description = "Whether the role participates in MCP persona resolution. Defaults to true; set false"
                        + " for roles with no MCP access so their callers land on the fallback by design.",
                example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean mcpPersonaEligible) {

    /** Eligible unless the caller explicitly says otherwise. */
    public boolean personaEligibleOrDefault() {
        return mcpPersonaEligible == null || mcpPersonaEligible;
    }
}

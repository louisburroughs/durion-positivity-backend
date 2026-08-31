package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.validation.PersonaText;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * Role update payload (#1613). No such endpoint existed before, so persona metadata authored at
 * creation time could never be corrected without a database edit.
 *
 * <p>PUT semantics: every field here replaces the stored value, and an omitted field clears it.
 * Clearing a persona slot is meaningful — it returns that slot to the derived default (D5) — so
 * "omitted means leave alone" would make the derived default unreachable through the API.
 *
 * <p>The role <em>name</em> is deliberately absent. Names key authority resolution, permission
 * grants, and the {@code system_prompts} rows in {@code pos-mcp-server}; renaming in place would
 * silently orphan all three. A rename is a create plus a delete.
 */
@Schema(description = "Replacement description and MCP persona metadata for an existing role")
public record RoleUpdateRequest(
        @Schema(
                description = "Human-readable description of the role. Doubles as the derived persona focus when"
                        + " personaFocus is omitted.",
                example = "Warranty claim intake and settlement",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Size(max = 500, message = "must be at most 500 characters")
        String description,

        @Schema(
                description = "Who the caller is, in the second person. Cleared, and therefore derived from the role"
                        + " name, when omitted.",
                example = "warranty clerk",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PersonaText(max = 60)
        String personaTitle,

        @Schema(
                description = "What the caller works on. Cleared, and therefore derived from the description, when"
                        + " omitted.",
                example = "warranty claim intake, eligibility, and settlement",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PersonaText(max = 200)
        String personaFocus,

        @Schema(
                description = "How to speak to the caller. Cleared, and therefore defaulted to a neutral tone, when"
                        + " omitted.",
                example = "precise, policy-aware, and explicit about claim status",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @PersonaText(max = 120)
        String personaTone,

        @Schema(
                description = "MCP persona resolution priority, lowest first. Null leaves the role unranked.",
                example = "45",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Short mcpPersonaRank,

        @Schema(
                description =
                        "Whether the role participates in MCP persona resolution. Defaults to true when" + " omitted.",
                example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean mcpPersonaEligible) {

    /** Eligible unless the caller explicitly says otherwise. */
    public boolean personaEligibleOrDefault() {
        return mcpPersonaEligible == null || mcpPersonaEligible;
    }
}

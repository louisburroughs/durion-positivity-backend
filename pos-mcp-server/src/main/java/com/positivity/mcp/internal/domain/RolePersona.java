package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.Nullable;

/**
 * One role's persona metadata as owned by {@code pos-security-service} (#1613).
 *
 * <p>Mirror of that service's {@code RolePersonaDto}. Slots are structured, never prompt text: the
 * template that turns them into a ROLE layer lives here, in {@link RolePersonaRenderer}, so prompt
 * wording stays an MCP concern with its own review loop and a role author cannot write instructions
 * straight into an assembled prompt.
 *
 * @param name role name, unprefixed and upper-case as stored upstream
 * @param description human-readable description; the derived persona focus when {@code personaFocus}
 *                    is absent
 * @param personaTitle who the caller is; derived from {@code name} when absent
 * @param personaFocus what the caller works on; derived from {@code description} when absent
 * @param personaTone how to speak to the caller; a neutral default when absent
 * @param mcpPersonaRank resolution priority, lowest first; null sorts after every ranked role
 * @param mcpPersonaEligible whether the role participates in persona resolution at all
 */
public record RolePersona(
        String name,
        @Nullable String description,
        @Nullable String personaTitle,
        @Nullable String personaFocus,
        @Nullable String personaTone,
        @Nullable Short mcpPersonaRank,
        boolean mcpPersonaEligible) {}

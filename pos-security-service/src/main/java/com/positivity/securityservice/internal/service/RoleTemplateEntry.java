package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.enums.LocationHierarchy;
import com.positivity.securityservice.internal.enums.LocationScope;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One role of the platform role template (ADR-0062 §6), detached from its platform-tenant row so
 * it can be applied under another tenant's binding: the canonical name, the MCP persona
 * attributes, the ADR-0061 location-scope attributes and the permission names it grants.
 */
public record RoleTemplateEntry(
        @NonNull String templateKey,
        @NonNull String name,
        @Nullable String description,
        @Nullable String personaTitle,
        @Nullable String personaFocus,
        @Nullable String personaTone,
        @Nullable Short mcpPersonaRank,
        boolean mcpPersonaEligible,
        @NonNull LocationScope locationScope,
        @NonNull LocationHierarchy locationHierarchy,
        @NonNull Set<String> permissionNames) {}

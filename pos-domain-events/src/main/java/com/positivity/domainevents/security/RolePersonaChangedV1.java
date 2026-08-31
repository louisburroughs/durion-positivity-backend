package com.positivity.domainevents.security;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a role's MCP persona metadata was created or changed (ADR-0044, issue #1613).
 *
 * <p>Published by pos-security-service on {@code security.events.v1} with
 * {@code eventType = "security.role.persona.changed"}; consumed by pos-mcp-server to refresh the
 * role it names without waiting for the next scheduled pull.
 *
 * <p>Carries the role's current persona state rather than a delta, so a consumer that missed an
 * earlier event still converges on the right answer from any single message. That also means the
 * event is safe to reprocess: applying it twice is the same as applying it once.
 *
 * <p>Slots are structured, never prompt text — the template that renders them lives in
 * pos-mcp-server (#1613 D1). Additions must be additive-only within schema version 1.
 *
 * @param roleId the role's id, and the envelope's aggregateId
 * @param name role name, unprefixed and upper-case as stored by the owner
 * @param description human-readable description; the consumer's derived persona focus when
 *                    {@code personaFocus} is absent
 * @param personaTitle who the caller is; derived by the consumer when absent
 * @param personaFocus what the caller works on; derived by the consumer when absent
 * @param personaTone how to speak to the caller; defaulted by the consumer when absent
 * @param mcpPersonaRank resolution priority, lowest first; null leaves the role unranked
 * @param mcpPersonaEligible whether the role participates in persona resolution at all
 */
public record RolePersonaChangedV1(
        @NonNull UUID roleId,
        @NonNull String name,
        @Nullable String description,
        @Nullable String personaTitle,
        @Nullable String personaFocus,
        @Nullable String personaTone,
        @Nullable Short mcpPersonaRank,
        boolean mcpPersonaEligible) {

    public static final String EVENT_TYPE = "security.role.persona.changed";
    public static final int SCHEMA_VERSION = 1;

    public RolePersonaChangedV1 {
        if (roleId == null) {
            throw new IllegalArgumentException("roleId must not be null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}

package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * Every role's MCP persona metadata, as one snapshot (#1613).
 *
 * <p>Wrapped rather than returned as a bare list so the response carries {@code generatedAt}: the
 * consumer swaps this in atomically and reports snapshot age, which is the signal that distinguishes
 * "synced and quiet" from "sync has been failing since boot".
 */
@Schema(description = "Snapshot of MCP persona metadata for every role")
public record RolePersonasResponse(
        @Schema(
                description = "When this snapshot was assembled, for staleness reporting by the consumer",
                example = "2026-08-31T15:24:09Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant generatedAt,

        @Schema(
                description = "Persona metadata for every role, ordered by rank then name. Ineligible roles are"
                        + " included and flagged, so the consumer can tell an excluded role from an unknown one.",
                requiredMode = Schema.RequiredMode.REQUIRED)
        List<RolePersonaDto> roles) {}

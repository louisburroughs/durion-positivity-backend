package com.positivity.shared.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Aggregate count result shared across domain services (the rung-1 "count convention" of the
 * answer resolution ladder — see {@code pos-mcp-server/docs/answer-resolution-ladder-design.md}).
 *
 * <p>{@code total} is the grand total; {@code groups} is the optional per-key breakdown (e.g.
 * status &rarr; count). Both are server-computed via COUNT queries — callers never page a full
 * collection to count it.
 */
@Schema(description = "Aggregate count result with an optional per-key breakdown.")
public record CountResponse(
        @Schema(description = "Grand total across all groups.", example = "42")
        long total,

        @Schema(description = "Optional per-key breakdown; empty when only a total is requested.") @NonNull
        List<CountGroup> groups) {

    @Schema(description = "A single keyed count within a breakdown.")
    public record CountGroup(
            @Schema(description = "Group key (e.g. a status name).", example = "WORK_IN_PROGRESS") @NonNull
            String key,

            @Schema(description = "Count for this key.", example = "7")
            long count) {}

    /** A total-only result with no breakdown. */
    @NonNull
    public static CountResponse of(long total) {
        return new CountResponse(total, List.of());
    }
}

package com.positivity.mcp.internal.service;

import java.util.Map;
import org.jspecify.annotations.NonNull;

/**
 * Gate 6 (G6.7): re-reads the current versions/ETags of the source entities captured at plan time,
 * so confirmation can detect stale data and force a re-preview. Keyed the same way the plan's
 * {@code source_entity_versions_json} map is keyed (entity ref → version token).
 */
public interface SourceEntityVersionProbe {

    /**
     * Returns the current version token for each captured entity ref. A returned map differing from
     * {@code captured} marks the plan stale. Implementations that cannot re-read a ref must echo the
     * captured value for it (no false staleness).
     */
    @NonNull
    Map<String, String> currentVersions(@NonNull String targetTool, @NonNull Map<String, String> captured);
}

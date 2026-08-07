package com.positivity.mcp.internal.service;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Default {@link SourceEntityVersionProbe}: echoes the captured versions (no re-read available, so
 * no staleness is ever reported). Replace with a {@code @Primary} probe as source APIs expose
 * version/ETag re-reads; the service-layer stale-data gate is already wired and unit-tested
 * against the interface.
 */
@Component
public class NoopSourceEntityVersionProbe implements SourceEntityVersionProbe {

    @Override
    public @NonNull Map<String, String> currentVersions(
            @NonNull String targetTool, @NonNull Map<String, String> captured) {
        return captured;
    }
}

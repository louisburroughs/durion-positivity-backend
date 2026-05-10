package com.positivity.mcp.internal.config;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.rag.preload")
public record StaticRagPreloadProperties(List<StaticDocEntry> docs) {

    public StaticRagPreloadProperties {
        docs = docs == null ? List.of() : docs;
    }

    public record StaticDocEntry(
            @NonNull String id, @NonNull String sourcePath, @Nullable String ragScope) {}
}

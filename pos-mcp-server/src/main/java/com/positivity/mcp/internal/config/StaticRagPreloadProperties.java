package com.positivity.mcp.internal.config;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mcp.rag.preload")
public record StaticRagPreloadProperties(List<StaticDocEntry> docs) {

    public StaticRagPreloadProperties {
        docs = docs == null ? List.of() : docs;
    }

    public record StaticDocEntry(@NonNull String id, @NonNull String sourcePath) {

        public StaticDocEntry {
            id = Objects.requireNonNull(id, "mcp.rag.preload.docs[].id must not be null");
            sourcePath = Objects.requireNonNull(sourcePath, "mcp.rag.preload.docs[].sourcePath must not be null");
        }
    }
}
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
            @NonNull String id,
            @NonNull String sourcePath,
            @Nullable String ragScope,
            @Nullable List<String> requiredPermissions) {

        /** Gate 5 G5.2: required-permissions are optional; default to none (public/AUTHENTICATED). */
        public StaticDocEntry {
            requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
        }

        /** Back-compat 3-arg form (no permission metadata). */
        public StaticDocEntry(@NonNull String id, @NonNull String sourcePath, @Nullable String ragScope) {
            this(id, sourcePath, ragScope, List.of());
        }
    }
}

package com.positivity.mcp.internal.config;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "mcp.rag.preload")
public record StaticRagPreloadProperties(List<StaticDocEntry> docs) {

    public StaticRagPreloadProperties {
        docs = docs == null ? List.of() : docs;
    }

    /**
     * @param entities ADR-0069 §3: the lexicon entity keys the document is about, or the single value
     *     {@code none} for a platform-wide document. Read by the scope graph only; empty when the
     *     entry does not declare it.
     */
    public record StaticDocEntry(
            @NonNull String id,
            @NonNull String sourcePath,
            @Nullable String ragScope,
            @Nullable List<String> requiredPermissions,
            @Nullable List<String> entities) {

        /** Gate 5 G5.2: required-permissions are optional; default to none (public/AUTHENTICATED). */
        @ConstructorBinding
        public StaticDocEntry {
            requiredPermissions = requiredPermissions == null ? List.of() : List.copyOf(requiredPermissions);
            entities = entities == null ? List.of() : List.copyOf(entities);
        }

        /** Back-compat 4-arg form (no entity annotation). */
        public StaticDocEntry(
                @NonNull String id,
                @NonNull String sourcePath,
                @Nullable String ragScope,
                @Nullable List<String> requiredPermissions) {
            this(id, sourcePath, ragScope, requiredPermissions, List.of());
        }

        /** Back-compat 3-arg form (no permission metadata). */
        public StaticDocEntry(@NonNull String id, @NonNull String sourcePath, @Nullable String ragScope) {
            this(id, sourcePath, ragScope, List.of(), List.of());
        }
    }
}

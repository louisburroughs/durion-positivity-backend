package com.positivity.mcp.internal.domain;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public final class RagScope {

    public static final String MASTER = "master";

    private RagScope() {}

    public static String normalize(@Nullable String rawScope) {
        if (rawScope == null || rawScope.trim().isEmpty()) {
            return MASTER;
        }
        return rawScope.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns a copy of {@code metadata} with the {@code rag_scope} key normalized via
     * {@link #normalize(String)}.
     */
    public static @NonNull Map<String, Object> normalizeInMetadata(@NonNull Map<String, Object> metadata) {
        LinkedHashMap<String, Object> normalized = new LinkedHashMap<>(metadata);
        Object rawScope = metadata.get("rag_scope");
        normalized.put("rag_scope", normalize(rawScope instanceof String s ? s : null));
        return normalized;
    }
}
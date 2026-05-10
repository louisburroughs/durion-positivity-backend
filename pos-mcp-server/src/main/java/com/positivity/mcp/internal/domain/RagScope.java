package com.positivity.mcp.internal.domain;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

public final class RagScope {

    public static final String MASTER = "master";

    private RagScope() {}

    public static String normalize(@Nullable String rawScope) {
        if (rawScope == null || rawScope.trim().isEmpty()) {
            return MASTER;
        }
        return rawScope.trim().toLowerCase(Locale.ROOT);
    }
}
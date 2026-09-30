package com.positivity.mcp.internal.scopegraph;

import java.util.Locale;
import org.jspecify.annotations.NonNull;

/** ADR-0069 §2: how a tool acts on an entity, the label of an {@code ACTS_ON} edge. */
public enum Access {
    READS,
    WRITES;

    /** The edge label: {@code reads} or {@code writes}. */
    public @NonNull String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** GET reads; every other method writes (§2, discovered operations). */
    public static @NonNull Access ofHttpMethod(@NonNull String httpMethod) {
        return "GET".equalsIgnoreCase(httpMethod.trim()) ? READS : WRITES;
    }
}

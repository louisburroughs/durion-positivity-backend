package com.positivity.mcp.internal.scopegraph;

import java.util.Comparator;
import org.jspecify.annotations.NonNull;

/**
 * Identity of a scope-graph node: its type plus the key that is unique within the type (ADR-0069
 * §2, "Identity" column).
 */
public record NodeId(@NonNull NodeType type, @NonNull String key) implements Comparable<NodeId> {

    private static final Comparator<NodeId> ORDER =
            Comparator.comparing(NodeId::type).thenComparing(NodeId::key);

    public NodeId {
        if (key.isBlank()) {
            throw new IllegalArgumentException("A " + type + " node needs a non-blank key");
        }
    }

    public static @NonNull NodeId of(@NonNull NodeType type, @NonNull String key) {
        return new NodeId(type, key);
    }

    @Override
    public int compareTo(@NonNull NodeId other) {
        return ORDER.compare(this, other);
    }

    /** {@code TYPE:key}, the form hashed into {@link ScopeGraph#contentHash()}. */
    @Override
    public @NonNull String toString() {
        return type + ":" + key;
    }
}

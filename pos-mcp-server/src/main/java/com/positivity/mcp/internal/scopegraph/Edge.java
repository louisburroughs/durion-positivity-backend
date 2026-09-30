package com.positivity.mcp.internal.scopegraph;

import java.util.Comparator;
import org.jspecify.annotations.NonNull;

/**
 * One directed, typed edge. {@code label} carries the edge attribute where the type has one: the
 * access ({@code reads} / {@code writes}) of an {@code ACTS_ON} edge, the relation name of a {@code
 * RELATES_TO} edge, and the required parameter of a {@code PRODUCES_INPUT_FOR} edge; it is empty
 * otherwise.
 */
public record Edge(
        @NonNull EdgeType type,
        @NonNull NodeId from,
        @NonNull NodeId to,
        @NonNull String label) implements Comparable<Edge> {

    private static final Comparator<Edge> ORDER = Comparator.comparing(Edge::type)
            .thenComparing(Edge::from)
            .thenComparing(Edge::to)
            .thenComparing(Edge::label);

    public Edge {
        if (!type.joins(from.type(), to.type())) {
            throw new IllegalArgumentException(type + " cannot join " + from + " to " + to);
        }
    }

    /** The access of an {@code ACTS_ON} edge. */
    public @NonNull Access access() {
        if (type != EdgeType.ACTS_ON) {
            throw new IllegalStateException("Only ACTS_ON edges carry an access, not " + type);
        }
        return Access.READS.label().equals(label) ? Access.READS : Access.WRITES;
    }

    @Override
    public int compareTo(@NonNull Edge other) {
        return ORDER.compare(this, other);
    }

    /** The form hashed into {@link ScopeGraph#contentHash()}. */
    @NonNull
    String key() {
        return type + "|" + from + "|" + to + "|" + label;
    }
}

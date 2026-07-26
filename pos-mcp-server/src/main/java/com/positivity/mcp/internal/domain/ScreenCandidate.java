package com.positivity.mcp.internal.domain;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A screen-registry row returned by a semantic nearest-neighbour search (rung 3 of the answer
 * resolution ladder). {@code score} is cosine similarity in {@code [-1, 1]} (1 = identical, and
 * negative for unrelated text) computed as {@code 1 - cosineDistance}; {@code requiredPerm} gates
 * whether the caller may be shown the screen.
 */
public record ScreenCandidate(
        @NonNull String screenKey,
        @NonNull String title,
        @NonNull String urlTemplate,
        @NonNull String domain,
        @Nullable String requiredPerm,
        double score) {}

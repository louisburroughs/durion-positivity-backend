package com.positivity.mcp.internal.domain;

import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * ADR-0068 spec §2.7 / ADR-0069 §5.4: what the acting tags hand the scope resolver as extra seeds. An
 * entity key seeds its {@code Entity} node with {@code MatchKind.TAG} (confidence {@code LOW}); the
 * RAG scope, when present, seeds the {@code Domain} node(s) mapped to it and their documents. Built
 * from the acting answers only, so in {@code off} and {@code shadow} it is always {@link #none()}.
 *
 * @param entityKeys lexicon entity keys whose acting {@code entity_<key>} Noul is {@code true}
 * @param ragScope the acting {@code domain} when it is not {@code master}, else null
 */
public record TagSeeds(
        @NonNull List<String> entityKeys, @Nullable String ragScope) {

    private static final TagSeeds NONE = new TagSeeds(List.of(), null);

    public TagSeeds {
        entityKeys = List.copyOf(entityKeys);
    }

    /** No tag seed: the three-argument resolution. */
    public static @NonNull TagSeeds none() {
        return NONE;
    }

    public boolean isEmpty() {
        return entityKeys.isEmpty() && ragScope == null;
    }
}

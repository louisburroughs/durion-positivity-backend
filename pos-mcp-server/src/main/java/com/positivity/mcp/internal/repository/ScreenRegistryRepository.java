package com.positivity.mcp.internal.repository;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Nearest-neighbour lookup over the screen registry (rung 3 of the answer resolution ladder). */
public interface ScreenRegistryRepository {

    /**
     * Return up to {@code limit} screens whose embedding is closest to {@code queryEmbedding},
     * ordered by descending cosine similarity. Rows without an embedding are ignored.
     *
     * @param queryEmbedding the embedded user message
     * @param domain         optional exact-domain restriction; {@code null} searches all domains
     * @param limit          maximum candidates to return
     */
    @NonNull
    List<ScreenCandidate> findNearest(float @NonNull [] queryEmbedding, @Nullable String domain, int limit);
}

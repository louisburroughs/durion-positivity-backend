package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.internal.repository.ScreenRegistryRepository;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Semantic screen resolver. Embeds the request, finds the nearest registered screens, and returns
 * the highest-scoring one that clears the similarity floor and that the caller is permitted to see.
 *
 * <p>Candidates come back ordered by descending similarity, so the first sub-floor candidate ends
 * the search (everything after it is also sub-floor). Permission-gated candidates are skipped, not
 * terminal — a lower-ranked but still-above-floor screen the caller can open still wins.
 */
@Service
@Profile({"!test", "openapi"})
public class ScreenLinkResolverImpl implements ScreenLinkResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenLinkResolverImpl.class);
    private static final int CANDIDATE_LIMIT = 5;

    private final EmbeddingModel embeddingModel;
    private final ScreenRegistryRepository repository;
    private final double minScore;

    public ScreenLinkResolverImpl(
            @NonNull EmbeddingModel embeddingModel,
            @NonNull ScreenRegistryRepository repository,
            @Value("${mcp.screen.min-score:0.6}") double minScore) {
        this.embeddingModel = embeddingModel;
        this.repository = repository;
        this.minScore = minScore;
    }

    @Override
    public @NonNull Optional<ScreenLink> resolve(
            @NonNull String userMessage,
            @Nullable String domain,
            @NonNull Set<String> callerPermissions,
            @NonNull Map<String, String> params) {
        if (userMessage.isBlank()) {
            return Optional.empty();
        }

        float[] queryEmbedding = embeddingModel.embed(userMessage);
        for (ScreenCandidate candidate : repository.findNearest(queryEmbedding, domain, CANDIDATE_LIMIT)) {
            if (candidate.score() < minScore) {
                break; // ordered by descending score — nothing below this clears the floor
            }
            if (candidate.requiredPerm() != null && !callerPermissions.contains(candidate.requiredPerm())) {
                continue; // caller can't open this screen; try the next best
            }
            String url = ScreenUrlTemplate.fill(candidate.urlTemplate(), params);
            LOGGER.debug(
                    "Resolved screen link screenKey={} score={} domain={}",
                    candidate.screenKey(),
                    candidate.score(),
                    domain);
            return Optional.of(new ScreenLink(candidate.screenKey(), candidate.title(), url, candidate.score()));
        }
        return Optional.empty();
    }
}

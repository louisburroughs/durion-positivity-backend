package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.internal.repository.ScreenRegistryRepository;
import com.positivity.mcp.service.SiteMapService;
import com.positivity.mcp.service.model.SiteMap;
import com.positivity.mcp.service.model.SiteMapSection;
import java.util.HashSet;
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
 *
 * <p>When the registry yields nothing the caller can open, resolution falls back to the frontend
 * site map: a coarse, section-level index of the app's navigable areas. Site-map sections are gated
 * by the caller's <em>roles</em> (a section with no {@code roles} is open to any authenticated
 * caller) and matched to the request lexically. This keeps top-level routes reachable across
 * frontend deployments without re-seeding the registry, while the registry stays the fine-grained,
 * semantic path. The fallback is best-effort — a site-map outage degrades to no result, never an
 * error.
 */
@Service
@Profile({"!test", "openapi"})
public class ScreenLinkResolverImpl implements ScreenLinkResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenLinkResolverImpl.class);
    private static final int CANDIDATE_LIMIT = 5;
    // Query/section tokens shorter than this are dropped as low-signal noise for the lexical fallback.
    private static final int MIN_TOKEN_LENGTH = 3;

    private final EmbeddingModel embeddingModel;
    private final ScreenRegistryRepository repository;

    @Nullable
    private final SiteMapService siteMapService;

    private final double minScore;
    private final double siteMapMinScore;

    public ScreenLinkResolverImpl(
            @NonNull EmbeddingModel embeddingModel,
            @NonNull ScreenRegistryRepository repository,
            @Nullable SiteMapService siteMapService,
            @Value("${mcp.screen.min-score:0.6}") double minScore,
            @Value("${mcp.screen.sitemap-min-score:0.5}") double siteMapMinScore) {
        this.embeddingModel = embeddingModel;
        this.repository = repository;
        this.siteMapService = siteMapService;
        this.minScore = minScore;
        this.siteMapMinScore = siteMapMinScore;
    }

    @Override
    public @NonNull Optional<ScreenLink> resolve(
            @NonNull String userMessage,
            @Nullable String domain,
            @NonNull Set<String> callerPermissions,
            @NonNull Set<String> callerRoles,
            @NonNull Map<String, String> params) {
        if (userMessage.isBlank()) {
            return Optional.empty();
        }

        Optional<ScreenLink> fromRegistry = resolveFromRegistry(userMessage, domain, callerPermissions, params);
        if (fromRegistry.isPresent()) {
            return fromRegistry;
        }
        return resolveFromSiteMap(userMessage, callerRoles);
    }

    private @NonNull Optional<ScreenLink> resolveFromRegistry(
            @NonNull String userMessage,
            @Nullable String domain,
            @NonNull Set<String> callerPermissions,
            @NonNull Map<String, String> params) {
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

    /**
     * Coarse role-gated fallback over the frontend site map. Best-effort: a site-map outage or an
     * empty match yields no result rather than an error.
     */
    private @NonNull Optional<ScreenLink> resolveFromSiteMap(
            @NonNull String userMessage, @NonNull Set<String> callerRoles) {
        if (siteMapService == null) {
            return Optional.empty();
        }
        SiteMap siteMap;
        try {
            siteMap = siteMapService.getSiteMap();
        } catch (RuntimeException exception) {
            LOGGER.debug("Site map unavailable for screen fallback: {}", exception.getMessage());
            return Optional.empty();
        }

        Set<String> queryTokens = tokenize(userMessage);
        if (queryTokens.isEmpty()) {
            return Optional.empty();
        }

        SiteMapSection best = null;
        double bestScore = 0;
        for (SiteMapSection section : siteMap.sections()) {
            if (!section.isVisibleTo(callerRoles)) {
                continue; // caller lacks a required role for this section
            }
            double score = lexicalScore(queryTokens, section);
            if (score > bestScore) {
                bestScore = score;
                best = section;
            }
        }

        if (best == null || bestScore < siteMapMinScore) {
            return Optional.empty();
        }
        LOGGER.debug("Resolved screen link from site map route={} score={}", best.route(), bestScore);
        // Site-map routes are static paths (no url_template placeholders); the route is the deep link.
        return Optional.of(new ScreenLink(best.route(), best.title(), best.route(), bestScore));
    }

    /**
     * Fraction of the request's tokens that appear in a section's searchable text (title +
     * description + route), in {@code [0, 1]}. A coverage measure, not a similarity score — this is a
     * deliberately simple lexical match for the section-level fallback, not the registry's semantic
     * search.
     */
    private static double lexicalScore(@NonNull Set<String> queryTokens, @NonNull SiteMapSection section) {
        Set<String> sectionTokens = tokenize(section.title() + " " + section.description() + " " + section.route());
        long hits = queryTokens.stream().filter(sectionTokens::contains).count();
        return (double) hits / queryTokens.size();
    }

    private static @NonNull Set<String> tokenize(@NonNull String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() >= MIN_TOKEN_LENGTH) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}

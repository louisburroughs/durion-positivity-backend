package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.internal.repository.ScreenRegistryRepository;
import com.positivity.mcp.service.SiteMapService;
import com.positivity.mcp.service.model.SiteMap;
import com.positivity.mcp.service.model.SiteMapSection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * site map: a coarse, section-level index of the app's navigable areas. The request is embedded once
 * and that same query vector is reused to rank site-map sections by cosine similarity (so the
 * fallback is semantic, consistent with the registry, not a separate lexical scheme). Sections are
 * gated by the caller's <em>roles</em> — a section with no {@code roles} is open to any authenticated
 * caller. This keeps top-level routes reachable across frontend deployments without re-seeding the
 * registry, while the registry stays the fine-grained path. The fallback is best-effort — a site-map
 * outage degrades to no result, never an error.
 *
 * <p>Section embeddings are pre-computed at startup by {@link SiteMapEmbeddingWarmupRunner} (via
 * {@link #warmUp()}) and cached by section text, so request-time matching is a pure cache read with
 * no embedding call on the hot path. A cache miss — warm-up never ran/failed, or a section appeared
 * after a frontend redeploy — falls back to a lazy embed and self-heals the cache for later requests.
 */
@Service
@Profile({"!test", "openapi"})
public class ScreenLinkResolverImpl implements ScreenLinkResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScreenLinkResolverImpl.class);
    private static final int CANDIDATE_LIMIT = 5;

    private final EmbeddingModel embeddingModel;
    private final ScreenRegistryRepository repository;

    @Nullable
    private final SiteMapService siteMapService;

    private final double minScore;
    private final double siteMapMinScore;

    // Section text → embedding. Bounded by the handful of distinct site-map section texts; keying on
    // the text means unchanged sections are embedded once and reused across requests and rebuilds.
    private final Map<String, float[]> sectionEmbeddingCache = new ConcurrentHashMap<>();

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

        // Embed once and reuse for both the registry search and the site-map fallback.
        float[] queryEmbedding = embeddingModel.embed(userMessage);
        Optional<ScreenLink> fromRegistry = resolveFromRegistry(queryEmbedding, domain, callerPermissions, params);
        if (fromRegistry.isPresent()) {
            return fromRegistry;
        }
        return resolveFromSiteMap(queryEmbedding, callerRoles);
    }

    private @NonNull Optional<ScreenLink> resolveFromRegistry(
            float @NonNull [] queryEmbedding,
            @Nullable String domain,
            @NonNull Set<String> callerPermissions,
            @NonNull Map<String, String> params) {
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
     * Coarse role-gated fallback over the frontend site map, ranking visible sections by cosine
     * similarity against the already-computed query embedding. Best-effort: a site-map outage or an
     * embedding failure yields no result rather than an error.
     */
    private @NonNull Optional<ScreenLink> resolveFromSiteMap(
            float @NonNull [] queryEmbedding, @NonNull Set<String> callerRoles) {
        if (siteMapService == null) {
            return Optional.empty();
        }
        try {
            SiteMap siteMap = siteMapService.getSiteMap();
            SiteMapSection best = null;
            double bestScore = 0;
            for (SiteMapSection section : siteMap.sections()) {
                if (!section.isVisibleTo(callerRoles)) {
                    continue; // caller lacks a required role for this section
                }
                double score = cosineSimilarity(queryEmbedding, sectionEmbedding(section));
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
        } catch (RuntimeException exception) {
            LOGGER.debug("Site map screen fallback unavailable: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Pre-embeds every current site-map section so request-time fallback matching is a pure cache
     * read. Invoked once at startup by {@link SiteMapEmbeddingWarmupRunner}. Best-effort — a site-map
     * outage leaves the cache to be filled lazily on first use, so warm-up never blocks startup.
     */
    public void warmUp() {
        if (siteMapService == null) {
            return;
        }
        try {
            SiteMap siteMap = siteMapService.getSiteMap();
            for (SiteMapSection section : siteMap.sections()) {
                sectionEmbedding(section);
            }
            LOGGER.info(
                    "Warmed site-map section embeddings for {} sections",
                    siteMap.sections().size());
        } catch (RuntimeException exception) {
            LOGGER.warn("Site-map embedding warm-up skipped: {}", exception.getMessage());
        }
    }

    private float @NonNull [] sectionEmbedding(@NonNull SiteMapSection section) {
        // Embed the natural-language title + description (not the route) for a meaningful vector.
        String text = section.title() + ". " + section.description();
        return sectionEmbeddingCache.computeIfAbsent(text, embeddingModel::embed);
    }

    /** Cosine similarity in {@code [-1, 1]}; 0 when either vector is zero or lengths differ. */
    private static double cosineSimilarity(float @NonNull [] a, float @NonNull [] b) {
        if (a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

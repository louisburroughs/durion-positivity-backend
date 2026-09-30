package com.positivity.mcp.internal.config;

import com.positivity.mcp.internal.domain.TagName;
import com.positivity.mcp.internal.domain.TaggingMode;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * ADR-0068 §5, §6 / spec §2.1: the question-tagging switches ({@code mcp.tagging}).
 *
 * <p>{@code mode} is one value for the whole seam; {@code enforced-tags} names the tags that act once
 * the mode is {@code enforce}, because §6 promotes each tag separately and one mode value cannot
 * express that (an addition to the ADR's key list, spec §2.1). {@code mode: enforce} with an empty
 * list behaves as {@code shadow}. Wave 1 records the list but never acts on it.
 *
 * <p>A bare {@code off} in YAML is the boolean {@code false}, not the string, so the YAML files write
 * the mode quoted or through a placeholder; {@link TaggingMode} is bound from the string.
 *
 * @param mode {@code off} (heuristic tagger only), {@code shadow} or {@code enforce}
 * @param enforcedTags tags (wire names) that act when {@code mode} is {@code enforce}
 * @param provider the System One endpoint (§5)
 * @param thresholds per-tag confidence threshold by tag name ({@code entity.<key>} for one entity);
 *     {@value #DEFAULT_THRESHOLD} otherwise
 * @param maxStateChars the message is cut at this length before it becomes the {@code state}
 * @param entityQuestions whether the {@code entity_<key>} Nouls are asked (default true); off fits a
 *     small-context model during the bake-off (13 questions instead of 44)
 */
@ConfigurationProperties(prefix = "mcp.tagging")
public record TaggingProperties(
        @Nullable TaggingMode mode,
        @Nullable List<String> enforcedTags,
        @Nullable Provider provider,
        @Nullable Map<String, Double> thresholds,
        int maxStateChars,
        @Nullable Boolean entityQuestions) {

    /** The pre-{@code entity-questions} shape: entity Nouls asked. */
    public TaggingProperties(
            @Nullable TaggingMode mode,
            @Nullable List<String> enforcedTags,
            @Nullable Provider provider,
            @Nullable Map<String, Double> thresholds,
            int maxStateChars) {
        this(mode, enforcedTags, provider, thresholds, maxStateChars, null);
    }

    /** ADR-0068 §1: every tag's threshold until shadow data sets per-tag values. */
    public static final double DEFAULT_THRESHOLD = 0.75;

    private static final int DEFAULT_MAX_STATE_CHARS = 4000;

    /**
     * The System One provider (ADR-0068 §5). The base URL is deliberately separate from {@code
     * spring.ai.ollama.base-url} so tagging can never follow the chat model off the cell.
     *
     * @param baseUrl the in-cell Ollama container by default
     * @param model a Jev-protocol decision model pulled into that container; the §6 bake-off sets it
     * @param timeout the connect + read latency budget; never raised to fit a slow model
     * @param apiKey for an external provider only; sent as {@code Authorization: Bearer}, never logged
     * @param keepAlive sent as {@code keep_alive} so the model stays resident between turns; omitted
     *     from the request when blank
     */
    public record Provider(
            @Nullable String baseUrl,
            @Nullable String model,
            @Nullable Duration timeout,
            @Nullable String apiKey,
            @Nullable String keepAlive) {

        public static final String DEFAULT_BASE_URL = "http://ollama:11434";
        public static final String DEFAULT_MODEL = "tev1:0.8b";
        public static final Duration DEFAULT_TIMEOUT = Duration.ofMillis(800);
        public static final String DEFAULT_KEEP_ALIVE = "30m";

        public Provider {
            baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : stripTrailingSlash(baseUrl);
            model = model == null || model.isBlank() ? DEFAULT_MODEL : model;
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? DEFAULT_TIMEOUT : timeout;
            apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
            keepAlive = keepAlive == null ? DEFAULT_KEEP_ALIVE : (keepAlive.isBlank() ? null : keepAlive);
        }

        /** The defaults: the in-cell container, {@code tev1:0.8b}, 800 ms, no key, 30 m keep-alive. */
        public static @NonNull Provider defaults() {
            return new Provider(null, null, null, null, null);
        }

        /** The host the failure log names (never the key, never the state). */
        public @NonNull String host() {
            try {
                java.net.URI uri = java.net.URI.create(baseUrl);
                return uri.getHost() == null ? baseUrl : uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
            } catch (IllegalArgumentException malformed) {
                return baseUrl;
            }
        }

        private static String stripTrailingSlash(String url) {
            String trimmed = url.trim();
            return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        }
    }

    /** Bound by Spring Boot (the record has a second, delegating constructor, as {@code StaticDocEntry} does). */
    @ConstructorBinding
    public TaggingProperties {
        mode = mode == null ? TaggingMode.OFF : mode;
        enforcedTags = enforcedTags == null
                ? List.of()
                : enforcedTags.stream()
                        .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                        .filter(tag -> !tag.isEmpty())
                        .distinct()
                        .toList();
        provider = provider == null ? Provider.defaults() : provider;
        Map<String, Double> bounded = new TreeMap<>();
        if (thresholds != null) {
            thresholds.forEach((tag, value) -> {
                if (value != null) {
                    bounded.put(tag.trim().toLowerCase(Locale.ROOT), Math.clamp(value, 0.0, 1.0));
                }
            });
        }
        thresholds = java.util.Collections.unmodifiableMap(bounded);
        if (maxStateChars <= 0) {
            maxStateChars = DEFAULT_MAX_STATE_CHARS;
        }
        entityQuestions = entityQuestions == null ? Boolean.TRUE : entityQuestions;
    }

    /** The defaults: mode {@code off}, nothing enforced, the in-cell provider. */
    public static @NonNull TaggingProperties off() {
        return new TaggingProperties(TaggingMode.OFF, List.of(), null, Map.of(), 0);
    }

    /** Mode {@code shadow} with the given provider and the other defaults. */
    public static @NonNull TaggingProperties shadow(@NonNull Provider provider) {
        return new TaggingProperties(TaggingMode.SHADOW, List.of(), provider, Map.of(), 0);
    }

    /** True in {@code shadow} and {@code enforce}: the decision model is called. */
    public boolean enabled() {
        return mode != TaggingMode.OFF;
    }

    /** True when {@code tag} acts: mode {@code enforce} and the tag is listed (Wave 2 reads this). */
    public boolean enforces(@NonNull TagName tag) {
        return mode == TaggingMode.ENFORCE && enforcedTags.contains(tag.wireName());
    }

    /** The confidence threshold of {@code tag} (ADR-0068 §1); every entity group shares {@code entity}'s. */
    public double thresholdFor(@NonNull TagName tag) {
        return thresholds.getOrDefault(tag.wireName(), DEFAULT_THRESHOLD);
    }

    /**
     * As {@link #thresholdFor(TagName)}, by wire name. An entity Noul {@code entity_<key>} takes
     * {@code thresholds.entity.<key>} when set, else {@code thresholds.entity}; an unknown name takes
     * the default.
     */
    public double thresholdFor(@NonNull String wireName) {
        Optional<String> entityKey = TagName.entityKey(wireName);
        if (entityKey.isPresent()) {
            Double perEntity = thresholds.get(TagName.ENTITY.wireName() + "." + entityKey.get());
            return perEntity != null ? perEntity : thresholdFor(TagName.ENTITY);
        }
        return TagName.fromWireName(wireName).map(this::thresholdFor).orElse(DEFAULT_THRESHOLD);
    }
}

package com.positivity.mcp.internal.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.config.SiteMapProperties;
import com.positivity.mcp.service.SiteMapService;
import com.positivity.mcp.service.SiteMapUnavailableException;
import com.positivity.mcp.service.model.SiteMap;
import com.positivity.mcp.service.model.SiteMapSection;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Pull-only consumer of the durion-positivity-frontend {@code /sitemap.json} artifact.
 *
 * <p>Fetches lazily over HTTP (no auth header — the artifact is a public static asset served ahead of
 * auth), validates against the shared contract, and caches with a TTL plus a last-known-good copy.
 * The frontend being briefly unavailable must not break this server: on a fetch failure with a warm
 * cache the previous copy is returned and its lifetime extended; only a cold-cache failure surfaces a
 * {@link SiteMapUnavailableException}.
 *
 * <p>Decoupling: no frontend source/types are imported; the only shared contract is the JSON schema
 * ({@code docs/sitemap/sitemap.schema.json}) and its integer {@code version}. A served {@code version}
 * higher than {@link SiteMapProperties#expectedVersion()} is tolerated — unknown fields are ignored
 * rather than crashing.
 */
@Component
public class SiteMapClient implements SiteMapService {

    private static final Logger log = LoggerFactory.getLogger(SiteMapClient.class);
    private static final String SITEMAP_PATH = "/sitemap.json";
    private static final String EXPECTED_APPLICATION = "durion-positivity-frontend";

    // Unknown fields are ignored so a forward-compatible version bump does not break parsing.
    private static final ObjectMapper MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient restClient;
    private final SiteMapProperties properties;
    private final Clock clock;
    private final Object refreshLock = new Object();

    private volatile @Nullable Cached cached;

    // Inject the shared Clock bean rather than Clock.systemUTC() so the accelerated-clock profile can
    // replace application time (enforced by the pos-archunit time rule).
    @Autowired
    public SiteMapClient(@NonNull SiteMapProperties properties, @NonNull Clock clock) {
        this(buildRestClient(properties), properties, clock);
    }

    // Visible for testing: inject a pre-built RestClient (e.g. bound to MockRestServiceServer) and a
    // controllable Clock.
    SiteMapClient(@NonNull RestClient restClient, @NonNull SiteMapProperties properties, @NonNull Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    private static RestClient buildRestClient(SiteMapProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.fetchTimeout());
        factory.setReadTimeout(properties.fetchTimeout());
        // A fresh builder (not the shared/load-balanced one) guarantees no bearer-token relay: the
        // frontend artifact is public and must be requested with no Authorization header.
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public @NonNull SiteMap getSiteMap() {
        Cached current = cached;
        if (current != null && !isExpired(current)) {
            return current.value();
        }
        synchronized (refreshLock) {
            Cached rechecked = cached;
            if (rechecked != null && !isExpired(rechecked)) {
                return rechecked.value(); // another thread refreshed while we waited on the lock
            }
            return fetchAndCacheOrFallback();
        }
    }

    @Override
    public @NonNull SiteMap refresh() {
        synchronized (refreshLock) {
            return fetchAndCacheOrFallback();
        }
    }

    @Override
    public @NonNull List<SiteMapSection> visibleSections(@NonNull Collection<String> userRoles) {
        Cached current = cached;
        if (current == null) {
            return List.of();
        }
        Set<String> roleSet = Set.copyOf(userRoles);
        return current.value().sections().stream()
                .filter(section -> section.isVisibleTo(roleSet))
                .toList();
    }

    private boolean isExpired(Cached entry) {
        return !clock.instant().isBefore(entry.expiresAt());
    }

    /** Must be called while holding {@link #refreshLock}. */
    private SiteMap fetchAndCacheOrFallback() {
        try {
            SiteMap fresh = doFetch();
            cached = new Cached(fresh, clock.instant().plus(properties.cacheTtl()));
            return fresh;
        } catch (RuntimeException ex) {
            Cached fallback = cached;
            if (fallback != null) {
                log.warn("Site-map fetch failed; serving last-known-good copy: {}", ex.getMessage());
                // Extend the served copy's lifetime so we don't hammer a down frontend every access.
                cached = new Cached(fallback.value(), clock.instant().plus(properties.cacheTtl()));
                return fallback.value();
            }
            log.warn("Site-map fetch failed and no cached copy is available: {}", ex.getMessage());
            if (ex instanceof SiteMapUnavailableException unavailable) {
                throw unavailable;
            }
            throw new SiteMapUnavailableException("Site map unavailable: " + ex.getMessage(), ex);
        }
    }

    private SiteMap doFetch() {
        ResponseEntity<String> response;
        try {
            response = restClient
                    .get()
                    .uri(SITEMAP_PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientException ex) {
            // Covers non-2xx (default error handler), connect/read timeouts, and connection errors.
            throw new SiteMapUnavailableException("Site-map request failed: " + ex.getMessage(), ex);
        }

        if (response.getStatusCode().value() != 200) {
            throw new SiteMapUnavailableException("Unexpected site-map status " + response.getStatusCode());
        }
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType == null
                || !contentType.getSubtype().toLowerCase(Locale.ROOT).contains("json")) {
            throw new SiteMapUnavailableException(
                    "Unexpected site-map " + HttpHeaders.CONTENT_TYPE + ": " + contentType);
        }
        String body = response.getBody();
        if (body == null || body.isBlank()) {
            throw new SiteMapUnavailableException("Empty site-map body");
        }

        SiteMap siteMap;
        try {
            siteMap = MAPPER.readValue(body, SiteMap.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new SiteMapUnavailableException("Malformed site-map body: " + ex.getOriginalMessage(), ex);
        }

        if (!EXPECTED_APPLICATION.equals(siteMap.application())) {
            throw new SiteMapUnavailableException("Unexpected site-map application '" + siteMap.application() + "'");
        }
        assertVersion(siteMap.version());
        log.debug(
                "Fetched site map: version={} generatedAt={} sections={}",
                siteMap.version(),
                siteMap.generatedAt(),
                siteMap.sections().size());
        return siteMap;
    }

    private void assertVersion(int version) {
        int expected = properties.expectedVersion();
        if (version > expected) {
            log.warn("Site-map version {} is newer than expected {}; using recognized fields only", version, expected);
        } else if (version < expected) {
            log.warn("Site-map version {} is older than expected {}; still usable", version, expected);
        }
    }

    /** A cached site map and the instant at which it becomes stale. */
    private record Cached(@NonNull SiteMap value, @NonNull Instant expiresAt) {}
}

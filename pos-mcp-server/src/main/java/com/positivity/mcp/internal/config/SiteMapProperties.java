package com.positivity.mcp.internal.config;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the frontend site-map consumer (see {@code docs/sitemap/client-module.md} in the
 * frontend repo).
 *
 * @param baseUrl origin of the frontend SSR server (NOT the API gateway); the SSR server defaults to
 *     port 4000. Bound from {@code FRONTEND_BASE_URL} in {@code application.yml}.
 * @param cacheTtl how long a fetched copy is served before a refresh is attempted
 * @param fetchTimeout per-request HTTP connect/read timeout
 * @param expectedVersion the contract {@code version} this build was written against; a higher
 *     version served by the frontend is tolerated (known fields only), not fatal
 */
@ConfigurationProperties(prefix = "mcp.sitemap")
public record SiteMapProperties(
        @NonNull String baseUrl, Duration cacheTtl, Duration fetchTimeout, int expectedVersion) {

    public SiteMapProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:4000";
        }
        if (cacheTtl == null) {
            cacheTtl = Duration.ofSeconds(600);
        }
        if (fetchTimeout == null) {
            fetchTimeout = Duration.ofSeconds(5);
        }
        if (expectedVersion < 1) {
            expectedVersion = 1;
        }
    }
}

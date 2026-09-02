package com.positivity.catalog.internal.config;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment YAML binding of the labor-guide provider roster (#1569 Phase 1, sourcing plan
 * §3.3/§5.1), mirroring the pos-supplier profile-config pattern including the base-url
 * override — which is exactly how Phase 1 points every adapter at {@code pos-reference-mock}
 * and how Phase 2 cuts over to a licensed vendor as pure configuration.
 *
 * <p>Example:
 *
 * <pre>{@code
 * pos:
 *   catalog:
 *     labor-guide:
 *       providers:
 *         - source-code: MOCKGUIDE
 *           adapter: mockguide
 *           base-url: http://pos-reference-mock:8095
 *           license-mode: STORE
 *           default-precedence: 100
 *         - source-code: MOCKGUIDE_LIVE
 *           adapter: mockguide
 *           base-url: http://pos-reference-mock:8095
 *           license-mode: QUERY_ONLY
 *           cache-ttl-seconds: 300
 * }</pre>
 *
 * @param enabled master switch; {@code false} ignores the whole roster (how prod/alpha keep
 *     the mock providers out without re-declaring the list). Null = true.
 * @param providers the configured sources; empty/absent = no labor-guide integration, every
 *     resolution falls through to stored DURION rows and default hours
 */
@ConfigurationProperties(prefix = "pos.catalog.labor-guide")
public record LaborGuideProviderProperties(
        @Nullable Boolean enabled, @Nullable List<ProviderSpec> providers) {

    /**
     * One configured source.
     *
     * @param sourceCode stable provenance code (e.g. {@code MOCKGUIDE}); uppercase by convention
     * @param adapter which adapter implementation speaks to this source (e.g. {@code mockguide})
     * @param baseUrl the vendor endpoint; overriding this is the sandbox/mock mechanism
     * @param licenseMode STORE or QUERY_ONLY (ADR-0058 §4); null defaults to STORE
     * @param enabled null = true
     * @param defaultPrecedence resolution tie-break when no policy row exists; null = 100
     * @param cacheTtlSeconds QUERY_ONLY live-answer cache TTL; null = 300
     */
    public record ProviderSpec(
            @Nullable String sourceCode,
            @Nullable String adapter,
            @Nullable String baseUrl,
            @Nullable String licenseMode,
            @Nullable Boolean enabled,
            @Nullable Integer defaultPrecedence,
            @Nullable Long cacheTtlSeconds) {}
}

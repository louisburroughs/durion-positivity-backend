package com.positivity.mcp.internal.service;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Startup warm-up for the site-map screen fallback: pre-embeds every site-map section so the first
 * request that falls through to the site map does no embedding on the hot path. Mirrors
 * {@link com.positivity.mcp.internal.config.ScreenEmbeddingInitializer}; fail-soft — {@link
 * ScreenLinkResolverImpl#warmUp()} swallows a site-map outage so startup never blocks.
 *
 * <p>Deliberately lives in {@code internal.service}, not {@code internal.config}: {@code
 * internal.service} already depends on {@code internal.config}, so a config-package runner depending
 * on this service bean would form a package-slice cycle that the ArchUnit rules reject.
 */
@Component
@Profile({"!test", "openapi"})
@Order(22)
public class SiteMapEmbeddingWarmupRunner implements ApplicationRunner {

    private final ScreenLinkResolverImpl screenLinkResolver;

    public SiteMapEmbeddingWarmupRunner(@NonNull ScreenLinkResolverImpl screenLinkResolver) {
        this.screenLinkResolver = screenLinkResolver;
    }

    @Override
    public void run(ApplicationArguments args) {
        screenLinkResolver.warmUp();
    }
}

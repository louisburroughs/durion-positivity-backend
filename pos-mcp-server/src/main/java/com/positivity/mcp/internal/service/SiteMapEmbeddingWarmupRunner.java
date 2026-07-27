package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.config.SiteMapProperties;
import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Warm-up for the site-map screen fallback: pre-embeds every site-map section so a request that
 * falls through to the site map does no embedding on the hot path.
 *
 * <p>Runs at startup ({@link ApplicationRunner}) and then periodically, on a fixed delay aligned to
 * the site-map cache TTL ({@code mcp.sitemap.cache-ttl}). The periodic re-warm re-fetches the (by
 * then stale) site map off the hot path so it (a) picks up sections added by a frontend redeploy and
 * (b) self-heals a cold cache if the frontend was unreachable at startup — all without ever embedding
 * on a request. {@link ScreenLinkResolverImpl#warmUp()} is fail-soft, so neither path blocks startup
 * nor disrupts serving. Section embeddings are keyed by text, so an unchanged re-warm is all cache
 * hits.
 *
 * <p>The periodic task is registered via {@link SchedulingConfigurer} using the bound TTL
 * {@link Duration} directly (rather than {@code @Scheduled(fixedDelayString = ...)}), so the cadence
 * tracks the TTL exactly with no dependence on the string's duration format.
 *
 * <p>Deliberately lives in {@code internal.service}, not {@code internal.config}: {@code
 * internal.service} already depends on {@code internal.config}, so a config-package bean depending on
 * this service bean would form a package-slice cycle that the ArchUnit rules reject.
 */
@Component
@Profile({"!test", "openapi"})
@Order(22)
public class SiteMapEmbeddingWarmupRunner implements ApplicationRunner, SchedulingConfigurer {

    private final ScreenLinkResolverImpl screenLinkResolver;
    private final Duration reWarmInterval;

    public SiteMapEmbeddingWarmupRunner(
            @NonNull ScreenLinkResolverImpl screenLinkResolver, @NonNull SiteMapProperties siteMapProperties) {
        this.screenLinkResolver = screenLinkResolver;
        this.reWarmInterval = siteMapProperties.cacheTtl();
    }

    @Override
    public void run(ApplicationArguments args) {
        screenLinkResolver.warmUp();
    }

    @Override
    public void configureTasks(@NonNull ScheduledTaskRegistrar registrar) {
        // Re-warm on the TTL cadence; first firing is one interval after startup (run() already
        // covered boot), so the two never double up.
        registrar.addFixedDelayTask(new IntervalTask(this::reWarm, reWarmInterval, reWarmInterval));
    }

    void reWarm() {
        screenLinkResolver.warmUp();
    }
}

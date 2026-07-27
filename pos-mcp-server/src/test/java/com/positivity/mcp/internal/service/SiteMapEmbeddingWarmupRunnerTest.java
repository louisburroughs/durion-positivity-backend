package com.positivity.mcp.internal.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.positivity.mcp.internal.config.SiteMapProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SiteMapEmbeddingWarmupRunner")
class SiteMapEmbeddingWarmupRunnerTest {

    private static final SiteMapProperties PROPERTIES =
            new SiteMapProperties("http://frontend", Duration.ofSeconds(600), Duration.ofSeconds(5), 1);

    @Test
    @DisplayName("run() triggers the resolver's site-map embedding warm-up")
    void runTriggersWarmUp() {
        ScreenLinkResolverImpl resolver = mock(ScreenLinkResolverImpl.class);

        new SiteMapEmbeddingWarmupRunner(resolver, PROPERTIES).run(null);

        verify(resolver).warmUp();
    }

    @Test
    @DisplayName("reWarm() triggers the resolver's site-map embedding warm-up")
    void reWarmTriggersWarmUp() {
        ScreenLinkResolverImpl resolver = mock(ScreenLinkResolverImpl.class);

        new SiteMapEmbeddingWarmupRunner(resolver, PROPERTIES).reWarm();

        verify(resolver).warmUp();
    }
}

package com.positivity.mcp.internal.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SiteMapEmbeddingWarmupRunner")
class SiteMapEmbeddingWarmupRunnerTest {

    @Test
    @DisplayName("run() triggers the resolver's site-map embedding warm-up")
    void runTriggersWarmUp() {
        ScreenLinkResolverImpl resolver = mock(ScreenLinkResolverImpl.class);

        new SiteMapEmbeddingWarmupRunner(resolver).run(null);

        verify(resolver).warmUp();
    }
}

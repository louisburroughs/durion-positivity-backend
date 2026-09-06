package com.positivity.mcp.internal.discovery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.config.ToolEmbeddingInitializer;
import com.positivity.mcp.internal.discovery.service.ToolRegistrationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

class DiscoveryRefreshSchedulerTest {

    private final ToolRegistrationService service = mock(ToolRegistrationService.class);
    private final ToolEmbeddingInitializer initializer = mock(ToolEmbeddingInitializer.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<ToolEmbeddingInitializer> initializerProvider = mock(ObjectProvider.class);

    private final DiscoveryRefreshScheduler scheduler = new DiscoveryRefreshScheduler(service, initializerProvider);

    @BeforeEach
    void wireProvider() {
        // ObjectProvider.ifAvailable is a default method that calls getIfAvailable; make the mock
        // behave like a provider with one bean present.
        when(initializerProvider.getIfAvailable()).thenReturn(initializer);
        doCallRealMethod().when(initializerProvider).ifAvailable(any());
    }

    @Test
    void refresh_invokesRegisterDiscoveredTools() {
        when(service.registerDiscoveredTools()).thenReturn(Mono.empty());

        scheduler.refresh();

        verify(service).registerDiscoveredTools();
    }

    @Test
    void refresh_triggersTheEmbeddingBackfill_afterASuccessfulCycle() {
        // #1824: rows a re-discovery cycle inserts have no embedding and are invisible to selection
        // until one is written; before this the only backfill was at start-up.
        when(service.registerDiscoveredTools()).thenReturn(Mono.empty());

        scheduler.refresh();

        verify(initializer).backfillAsync();
    }

    @Test
    void refresh_doesNotBackfill_whenRegistrationErrors() {
        when(service.registerDiscoveredTools()).thenReturn(Mono.error(new RuntimeException("boom")));

        scheduler.refresh();

        verify(initializer, never()).backfillAsync();
    }

    @Test
    void refresh_toleratesNoInitializerBean() {
        // The initializer is @Profile("!test"); the scheduler must not need it.
        when(initializerProvider.getIfAvailable()).thenReturn(null);
        when(service.registerDiscoveredTools()).thenReturn(Mono.empty());

        scheduler.refresh();

        verify(initializer, never()).backfillAsync();
    }

    @Test
    void refresh_isFailSoft_whenRegistrationErrors() {
        when(service.registerDiscoveredTools()).thenReturn(Mono.error(new RuntimeException("boom")));

        // Must not throw — the refresh swallows errors so a bad cycle never disrupts serving.
        scheduler.refresh();

        verify(service).registerDiscoveredTools();
    }
}

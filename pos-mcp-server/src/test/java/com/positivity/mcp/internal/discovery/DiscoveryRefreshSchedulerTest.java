package com.positivity.mcp.internal.discovery;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.discovery.service.ToolRegistrationService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

class DiscoveryRefreshSchedulerTest {

    private final ToolRegistrationService service = mock(ToolRegistrationService.class);
    private final DiscoveryRefreshScheduler scheduler = new DiscoveryRefreshScheduler(service);

    @Test
    void refresh_invokesRegisterDiscoveredTools() {
        when(service.registerDiscoveredTools()).thenReturn(Mono.empty());

        scheduler.refresh();

        verify(service).registerDiscoveredTools();
    }

    @Test
    void refresh_isFailSoft_whenRegistrationErrors() {
        when(service.registerDiscoveredTools()).thenReturn(Mono.error(new RuntimeException("boom")));

        // Must not throw — the refresh swallows errors so a bad cycle never disrupts serving.
        scheduler.refresh();

        verify(service).registerDiscoveredTools();
    }
}

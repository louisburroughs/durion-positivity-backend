package com.positivity.location.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BaySpecialtyMapStartupPublisher} republishes every active tenant's map at boot and never
 * lets a failure escape and block startup.
 */
class BaySpecialtyMapStartupPublisherTest {

    private final BaySpecialtyMapPublisher publisher = mock(BaySpecialtyMapPublisher.class);

    @Test
    @DisplayName("Republishes the current map for every active tenant via TenantIterator")
    void republishesEveryActiveTenant() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantRegistry registry = mock(TenantRegistry.class);
        when(registry.snapshot()).thenReturn(new TenantRegistry.Snapshot(List.of(tenantA, tenantB), true));
        TenantIterator iterator = new TenantIterator(registry);

        new BaySpecialtyMapStartupPublisher(iterator, publisher).run(null);

        verify(publisher).publishCurrent(tenantA);
        verify(publisher).publishCurrent(tenantB);
    }

    @Test
    @DisplayName("A failure for one tenant does not stop the others, and never escapes to block startup")
    void tenantFailureNeverBlocksStartup() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        TenantRegistry registry = mock(TenantRegistry.class);
        when(registry.snapshot()).thenReturn(new TenantRegistry.Snapshot(List.of(failing, ok), true));
        TenantIterator iterator = new TenantIterator(registry);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(publisher)
                .publishCurrent(failing);

        new BaySpecialtyMapStartupPublisher(iterator, publisher).run(null);

        verify(publisher).publishCurrent(failing);
        verify(publisher).publishCurrent(ok);
    }

    @Test
    @DisplayName("An empty registry logs and never throws")
    void emptyRegistryIsHarmless() {
        TenantRegistry registry = mock(TenantRegistry.class);
        when(registry.snapshot()).thenReturn(new TenantRegistry.Snapshot(List.of(), true));
        TenantIterator iterator = new TenantIterator(registry);

        new BaySpecialtyMapStartupPublisher(iterator, publisher).run(null);

        verify(publisher, times(0)).publishCurrent(any());
    }
}

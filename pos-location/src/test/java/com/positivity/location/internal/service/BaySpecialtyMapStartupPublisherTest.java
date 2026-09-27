package com.positivity.location.internal.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * {@link BaySpecialtyMapStartupPublisher} republishes every active tenant's map at boot, backfills a
 * tenant this module never provisioned before publishing for it, and never lets a failure escape and
 * block startup.
 */
class BaySpecialtyMapStartupPublisherTest {

    private static final List<BaySpecialtyMapProvisioningService.PlatformRow> PLATFORM_ROWS =
            List.of(new BaySpecialtyMapProvisioningService.PlatformRow("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"));

    private final BaySpecialtyMapPublisher publisher = mock(BaySpecialtyMapPublisher.class);
    private final BaySpecialtyMapProvisioningService provisioningService =
            mock(BaySpecialtyMapProvisioningService.class);

    private BaySpecialtyMapStartupPublisher startupPublisher(TenantIterator iterator) {
        when(provisioningService.readPlatformTemplate()).thenReturn(PLATFORM_ROWS);
        return new BaySpecialtyMapStartupPublisher(iterator, publisher, provisioningService);
    }

    private TenantIterator iteratorOver(UUID... tenantIds) {
        TenantRegistry registry = mock(TenantRegistry.class);
        when(registry.snapshot()).thenReturn(new TenantRegistry.Snapshot(List.of(tenantIds), true));
        return new TenantIterator(registry);
    }

    @Test
    @DisplayName("A tenant with an empty map is backfilled from the platform template and not republished again")
    void emptyTenantIsBackfilled() {
        UUID tenant = UUID.randomUUID();
        when(provisioningService.provisionIfMissing(tenant, PLATFORM_ROWS)).thenReturn(true);

        startupPublisher(iteratorOver(tenant)).run(null);

        verify(provisioningService).provisionIfMissing(tenant, PLATFORM_ROWS);
        // provisionIfMissing already published (via BaySpecialtyMapPublisher.publishChanged
        // internally); the sweep must not also call publishCurrent for the same tenant.
        verify(publisher, never()).publishCurrent(tenant);
    }

    @Test
    @DisplayName("A tenant that already has a map is left untouched and simply republished")
    void nonEmptyTenantIsOnlyRepublished() {
        UUID tenant = UUID.randomUUID();
        when(provisioningService.provisionIfMissing(tenant, PLATFORM_ROWS)).thenReturn(false);

        startupPublisher(iteratorOver(tenant)).run(null);

        verify(provisioningService).provisionIfMissing(tenant, PLATFORM_ROWS);
        verify(publisher).publishCurrent(tenant);
    }

    @Test
    @DisplayName("The platform template is read once, before the sweep, not once per tenant")
    void platformTemplateReadOnce() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        startupPublisher(iteratorOver(tenantA, tenantB)).run(null);

        verify(provisioningService, times(1)).readPlatformTemplate();
    }

    @Test
    @DisplayName("A failure for one tenant does not stop the others, and never escapes to block startup")
    void tenantFailureNeverBlocksStartup() {
        UUID failing = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        when(provisioningService.provisionIfMissing(any(), any())).thenReturn(false);
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(publisher)
                .publishCurrent(failing);

        startupPublisher(iteratorOver(failing, ok)).run(null);

        verify(publisher).publishCurrent(failing);
        verify(publisher).publishCurrent(ok);
    }

    @Test
    @DisplayName("An empty registry logs and never throws")
    void emptyRegistryIsHarmless() {
        startupPublisher(iteratorOver()).run(null);

        verify(publisher, times(0)).publishCurrent(any());
        verify(provisioningService, times(0)).provisionIfMissing(any(), any());
    }

    @Test
    @DisplayName("readPlatformTemplate failing is swallowed and never blocks startup")
    void platformTemplateReadFailureNeverBlocksStartup() {
        TenantIterator iterator = iteratorOver(UUID.randomUUID());
        BaySpecialtyMapProvisioningService failingProvisioningService = mock(BaySpecialtyMapProvisioningService.class);
        when(failingProvisioningService.readPlatformTemplate()).thenThrow(new RuntimeException("boom"));

        new BaySpecialtyMapStartupPublisher(iterator, publisher, failingProvisioningService).run(null);

        verify(publisher, never()).publishCurrent(any());
        verify(failingProvisioningService, times(1)).readPlatformTemplate();
    }
}

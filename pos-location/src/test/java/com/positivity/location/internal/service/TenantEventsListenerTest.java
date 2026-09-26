package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TenantEventsListener}'s contract: only {@code tenant.created} provisions, the platform
 * template is read (via {@link BaySpecialtyMapProvisioningService#readPlatformTemplate()}, whose own
 * "read under the platform tenant" contract is proven in {@code BaySpecialtyMapProvisioningServiceTest})
 * before the new tenant is bound, and provisioning then runs entirely under that new tenant's
 * binding.
 */
class TenantEventsListenerTest {

    private static final UUID TENANT_ID = UUID.fromString("01990000-0000-7000-8000-0000000000c1");

    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final BaySpecialtyMapProvisioningService provisioningService =
            mock(BaySpecialtyMapProvisioningService.class);

    private TenantEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new TenantEventsListener(new ObjectMapper(), processedEventRepository, provisioningService);
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(provisioningService.readPlatformTemplate()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String tenantCreated(String eventId, String tenantId) {
        return """
                {"eventId":"%s","eventType":"tenant.created","aggregateVersion":1,
                 "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"PENDING",
                            "initialAdminEmail":"owner@acme.example"}}
                """.formatted(eventId, tenantId);
    }

    @Test
    @DisplayName("tenant.created reads the platform template and provisions the new tenant")
    void tenantCreatedProvisions() {
        when(provisioningService.readPlatformTemplate())
                .thenReturn(List.of(
                        new BaySpecialtyMapProvisioningService.PlatformRow("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL")));

        listener.onTenantEvent(tenantCreated("01990000-0000-7000-8000-0000000000d1", TENANT_ID.toString()));

        verify(provisioningService)
                .provisionIfNeeded(
                        eq(TENANT_ID),
                        eq("01990000-0000-7000-8000-0000000000d1"),
                        eq(List.of(new BaySpecialtyMapProvisioningService.PlatformRow(
                                "ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"))));
        // The platform read and the provisioning call must not leave a tenant bound afterwards.
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    @DisplayName("The platform template is read before the new tenant is bound")
    void platformTemplateReadBeforeTenantBound() {
        when(provisioningService.readPlatformTemplate()).thenAnswer(invocation -> {
            // readPlatformTemplate switches to the platform tenant and restores afterwards
            // (proven in BaySpecialtyMapProvisioningServiceTest); from the listener's own thread,
            // at the moment it calls this, nothing should be bound yet.
            assertThat(TenantContext.current()).isEmpty();
            return List.of();
        });

        listener.onTenantEvent(tenantCreated("01990000-0000-7000-8000-0000000000d2", TENANT_ID.toString()));

        verify(provisioningService).provisionIfNeeded(eq(TENANT_ID), any(), eq(List.of()));
    }

    @Test
    @DisplayName("provisionIfNeeded itself runs with the new tenant bound")
    void provisioningRunsUnderNewTenant() {
        org.mockito.Mockito.doAnswer(invocation -> {
                    assertThat(TenantContext.current()).contains(TENANT_ID);
                    return null;
                })
                .when(provisioningService)
                .provisionIfNeeded(eq(TENANT_ID), any(), any());

        listener.onTenantEvent(tenantCreated("01990000-0000-7000-8000-0000000000d7", TENANT_ID.toString()));

        verify(provisioningService).provisionIfNeeded(eq(TENANT_ID), any(), any());
    }

    @Test
    @DisplayName("Other tenant lifecycle facts on the same topic are skipped without provisioning")
    void otherEventTypesSkipped() {
        listener.onTenantEvent("""
                {"eventId":"01990000-0000-7000-8000-0000000000d3","eventType":"tenant.suspended",
                 "aggregateVersion":2,
                 "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"SUSPENDED"}}
                """.formatted(TENANT_ID));

        verify(provisioningService, never()).provisionIfNeeded(any(), any(), any());
        verify(provisioningService, never()).readPlatformTemplate();
    }

    @Test
    @DisplayName("A redelivered eventId is skipped before any tenant is bound")
    void duplicateEventSkipped() {
        when(processedEventRepository.existsById("01990000-0000-7000-8000-0000000000d4"))
                .thenReturn(true);

        listener.onTenantEvent(tenantCreated("01990000-0000-7000-8000-0000000000d4", TENANT_ID.toString()));

        verify(provisioningService, never()).provisionIfNeeded(any(), any(), any());
        verify(provisioningService, never()).readPlatformTemplate();
    }

    @Test
    @DisplayName("An unparsable message is dropped rather than poisoning the partition")
    void unparsableMessageDropped() {
        listener.onTenantEvent("not json at all");

        verify(provisioningService, never()).provisionIfNeeded(any(), any(), any());
    }

    @Test
    @DisplayName("A tenant.created with no eventId is dropped")
    void missingEventIdDropped() {
        listener.onTenantEvent("""
                {"eventType":"tenant.created","aggregateVersion":1,
                 "payload":{"tenantId":"%s","slug":"acme","status":"PENDING","initialAdminEmail":"a@b.example"}}
                """.formatted(TENANT_ID));

        verify(provisioningService, never()).provisionIfNeeded(any(), any(), any());
    }

    @Test
    @DisplayName("A malformed tenant.created (no projection) is logged and dropped")
    void malformedPayloadDropped() {
        listener.onTenantEvent("""
                {"eventId":"01990000-0000-7000-8000-0000000000d5","eventType":"tenant.created",
                 "aggregateVersion":1,"payload":{"tenantId":"not-a-uuid"}}
                """);

        verify(provisioningService, never()).provisionIfNeeded(any(), any(), any());
    }

    @Test
    @DisplayName("Nesting restores any previously-bound tenant rather than leaving the new tenant bound")
    void restoresPreviouslyBoundTenant() {
        UUID caller = UUID.fromString("01990000-0000-7000-8000-0000000000ca");
        TenantContext.bind(caller);
        try {
            listener.onTenantEvent(tenantCreated("01990000-0000-7000-8000-0000000000d6", TENANT_ID.toString()));
            assertThat(TenantContext.current()).contains(caller);
        } finally {
            TenantContext.clear();
        }
    }
}

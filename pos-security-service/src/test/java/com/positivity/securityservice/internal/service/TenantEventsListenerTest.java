package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class TenantEventsListenerTest {

    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final UUID TENANT = UUID.fromString("01990000-0000-7000-8000-000000000123");

    private final ExtTenantRepository extTenants = mock(ExtTenantRepository.class);
    private final ProcessedEventRepository processed = mock(ProcessedEventRepository.class);
    private final TenantEventsListener.TenantReplicaApplier applier =
            new TenantEventsListener.TenantReplicaApplier(extTenants, processed, Clock.fixed(NOW, ZoneOffset.UTC));
    private final RoleTemplateService roleTemplateService = mock(RoleTemplateService.class);
    private final TenantProvisioningService provisioningService = mock(TenantProvisioningService.class);
    private final TenantEventsListener listener =
            new TenantEventsListener(new ObjectMapper(), applier, roleTemplateService, provisioningService);

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private static String created(String id, String email) {
        return """
                {"eventId":"%s","eventType":"tenant.created","schemaVersion":1,"aggregateId":"%s","aggregateVersion":1,
                 "occurredAtUtc":"2026-09-10T12:00:00Z","sourceService":"pos-tenant",
                 "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"PENDING"%s}}
                """.formatted(id, TENANT, TENANT, email == null ? "" : ",\"initialAdminEmail\":\"" + email + "\"");
    }

    @Test
    @DisplayName("tenant.created provisions: template read as platform, applied as the new tenant, then recorded")
    void createdProvisionsTheTenant() {
        when(processed.existsById("c1")).thenReturn(false);
        when(extTenants.findById(TENANT)).thenReturn(Optional.empty());
        List<RoleTemplateEntry> template = List.of();
        AtomicReference<UUID> snapshotTenant = new AtomicReference<>();
        AtomicReference<UUID> provisionTenant = new AtomicReference<>();
        when(roleTemplateService.snapshot()).thenAnswer(inv -> {
            snapshotTenant.set(TenantContext.require());
            return template;
        });
        when(provisioningService.provision(any(), any(), any())).thenAnswer(inv -> {
            provisionTenant.set(TenantContext.require());
            return new TenantProvisioningService.Outcome(6, true);
        });

        listener.onEvent(created("c1", "owner@acme.example"));

        assertThat(snapshotTenant.get()).isEqualTo(PlatformTenant.ID);
        assertThat(provisionTenant.get()).isEqualTo(TENANT);
        verify(provisioningService).provision(TENANT, "owner@acme.example", template);
        verify(extTenants).save(any(ExtTenant.class));
        verify(processed).save(any(ProcessedEvent.class));
        assertThat(TenantContext.isBound()).as("bindings restored").isFalse();
    }

    @Test
    @DisplayName("no provisioning on a redelivery, on other facts, or without the administrator's email")
    void provisioningIsGuarded() {
        when(processed.existsById("c2")).thenReturn(true);
        listener.onEvent(created("c2", "owner@acme.example"));

        when(processed.existsById("c3")).thenReturn(false);
        when(extTenants.findById(TENANT)).thenReturn(Optional.empty());
        listener.onEvent(created("c3", null));

        when(processed.existsById("u1")).thenReturn(false);
        listener.onEvent(event("u1", "tenant.updated", 2, "ACTIVE"));

        verify(provisioningService, never()).provision(any(), any(), any());
        verify(roleTemplateService, never()).snapshot();
        verify(processed, org.mockito.Mockito.times(2)).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("a provisioning failure is not recorded, so the fact is redelivered")
    void provisioningFailureIsNotRecorded() {
        when(processed.existsById("c4")).thenReturn(false);
        when(roleTemplateService.snapshot()).thenReturn(List.of());
        when(provisioningService.provision(any(), any(), any())).thenThrow(new IllegalStateException("template"));

        assertThatThrownBy(() -> listener.onEvent(created("c4", "owner@acme.example")))
                .isInstanceOf(IllegalStateException.class);
        verify(processed, never()).save(any());
    }

    private static String event(String id, String type, long version, String status) {
        return """
                {"eventId":"%s","eventType":"%s","schemaVersion":1,"aggregateId":"%s","aggregateVersion":%d,
                 "occurredAtUtc":"2026-09-10T12:00:00Z","sourceService":"pos-tenant",
                 "payload":{"tenantId":"%s","slug":"acme","displayName":"Acme","status":"%s"}}
                """.formatted(id, type, TENANT, version, TENANT, status);
    }

    @Test
    @DisplayName("a projection fact inserts the replica row and records the event")
    void insertsANewRow() {
        when(processed.existsById("e1")).thenReturn(false);
        when(extTenants.findById(TENANT)).thenReturn(Optional.empty());

        listener.onEvent(event("e1", "tenant.created", 1, "PENDING"));

        ArgumentCaptor<ExtTenant> saved = ArgumentCaptor.forClass(ExtTenant.class);
        verify(extTenants).save(saved.capture());
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getValue().getSlug()).isEqualTo("acme");
        assertThat(saved.getValue().getStatus()).isEqualTo("PENDING");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(1L);
        assertThat(saved.getValue().getUpdatedAt()).isEqualTo(NOW);
        ArgumentCaptor<ProcessedEvent> ledger = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processed).save(ledger.capture());
        assertThat(ledger.getValue().getEventId()).isEqualTo("e1");
        assertThat(ledger.getValue().getOwner()).isEqualTo("tenant");
    }

    @Test
    @DisplayName("an older fact never overwrites a newer row, but is still recorded")
    void olderFactIsIgnored() {
        when(processed.existsById("e2")).thenReturn(false);
        when(extTenants.findById(TENANT))
                .thenReturn(Optional.of(ExtTenant.builder()
                        .tenantId(TENANT)
                        .slug("acme")
                        .status("ACTIVE")
                        .aggregateVersion(5L)
                        .build()));

        listener.onEvent(event("e2", "tenant.suspended", 3, "SUSPENDED"));

        verify(extTenants, never()).save(any());
        verify(processed).save(any(ProcessedEvent.class));
    }

    @Test
    void newerFactUpdatesTheRowInPlace() {
        when(processed.existsById("e3")).thenReturn(false);
        ExtTenant row = ExtTenant.builder()
                .tenantId(TENANT)
                .slug("acme")
                .status("ACTIVE")
                .aggregateVersion(5L)
                .build();
        when(extTenants.findById(TENANT)).thenReturn(Optional.of(row));

        listener.onEvent(event("e3", "tenant.suspended", 6, "SUSPENDED"));

        verify(extTenants).save(row);
        assertThat(row.getStatus()).isEqualTo("SUSPENDED");
        assertThat(row.getAggregateVersion()).isEqualTo(6L);
    }

    @Test
    @DisplayName("tenant.provisioned carries no projection: recorded, nothing written; redelivery is a no-op")
    void provisionedAndRedelivery() {
        when(processed.existsById("e4")).thenReturn(false);
        listener.onEvent("""
                {"eventId":"e4","eventType":"tenant.provisioned","payload":{"tenantId":"%s"}}
                """.formatted(TENANT));
        verify(extTenants, never()).save(any());
        verify(processed).save(any(ProcessedEvent.class));

        when(processed.existsById("e1")).thenReturn(true);
        listener.onEvent(event("e1", "tenant.created", 1, "PENDING"));
        verify(extTenants, never()).findById(any());
    }

    @Test
    void garbageIsDroppedAndTransientFailuresPropagate() {
        listener.onEvent("not json");
        listener.onEvent("{\"eventType\":\"tenant.updated\"}");
        verify(processed, never()).save(any());

        when(processed.existsById("e5")).thenReturn(false);
        doThrow(new QueryTimeoutException("slow")).when(extTenants).findById(any());
        assertThatThrownBy(() -> listener.onEvent(event("e5", "tenant.updated", 2, "ACTIVE")))
                .isInstanceOf(QueryTimeoutException.class);
    }
}

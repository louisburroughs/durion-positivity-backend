package com.positivity.tenant.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.tenant.TenantCreatedV1;
import com.positivity.domainevents.tenant.TenantEventTypes;
import com.positivity.domainevents.tenant.TenantProjectionV1;
import com.positivity.tenant.internal.config.OutboxEventWriter;
import com.positivity.tenant.internal.entity.TenantEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class TenantFactPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-10T12:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);

    private TenantFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new TenantFactPublisher(writerProvider, TEST_CLOCK, "tenant.events.v1");
    }

    private static TenantEntity tenant(TenantStatus status) {
        return TenantEntity.builder()
                .id(UUID.fromString("01990000-0000-7000-8000-000000000123"))
                .slug("acme")
                .displayName("Acme")
                .status(status)
                .accountId(UUID.randomUUID())
                .initialAdminEmail("owner@acme.example")
                .version(4L)
                .build();
    }

    @SuppressWarnings("unchecked")
    private DomainEventEnvelope<Object> captured() {
        ArgumentCaptor<DomainEventEnvelope<Object>> captor = ArgumentCaptor.captor();
        verify(writer).publish(eq("tenant.events.v1"), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("tenant.created carries the projection plus the provisioning input, keyed by tenant id")
    void created() {
        publisher.tenantCreated(tenant(TenantStatus.PENDING));

        DomainEventEnvelope<Object> envelope = captured();
        assertThat(envelope.eventType()).isEqualTo(TenantEventTypes.CREATED);
        assertThat(envelope.aggregateId()).isEqualTo(UUID.fromString("01990000-0000-7000-8000-000000000123"));
        assertThat(envelope.aggregateVersion()).isEqualTo(4L);
        assertThat(envelope.sourceService()).isEqualTo("pos-tenant");
        assertThat(envelope.occurredAtUtc()).isEqualTo(Instant.parse("2026-09-10T12:00:00Z"));
        TenantCreatedV1 payload = (TenantCreatedV1) envelope.payload();
        assertThat(payload.status()).isEqualTo("PENDING");
        assertThat(payload.initialAdminEmail()).isEqualTo("owner@acme.example");
        assertThat(payload.projection().slug()).isEqualTo("acme");
    }

    @Test
    @DisplayName("status moves carry the public projection only")
    void statusFacts() {
        publisher.tenantSuspended(tenant(TenantStatus.SUSPENDED));
        DomainEventEnvelope<Object> envelope = captured();
        assertThat(envelope.eventType()).isEqualTo(TenantEventTypes.SUSPENDED);
        assertThat(envelope.payload()).isInstanceOf(TenantProjectionV1.class);
        assertThat(((TenantProjectionV1) envelope.payload()).status()).isEqualTo("SUSPENDED");
    }

    @Test
    void everyStatusMoveHasItsOwnType() {
        publisher.tenantUpdated(tenant(TenantStatus.ACTIVE));
        publisher.tenantReactivated(tenant(TenantStatus.ACTIVE));
        publisher.tenantDecommissioned(tenant(TenantStatus.DECOMMISSIONED));

        ArgumentCaptor<DomainEventEnvelope<Object>> captor = ArgumentCaptor.captor();
        verify(writer, org.mockito.Mockito.times(3)).publish(eq("tenant.events.v1"), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(DomainEventEnvelope::eventType)
                .containsExactly(
                        TenantEventTypes.UPDATED, TenantEventTypes.REACTIVATED, TenantEventTypes.DECOMMISSIONED);
    }

    @Test
    @DisplayName("with Kafka off the writer bean is absent and nothing is queued")
    void noWriterNoFact() {
        when(writerProvider.getIfAvailable()).thenReturn(null);
        publisher.tenantCreated(tenant(TenantStatus.PENDING));
        verifyNoInteractions(writer);
    }

    @Test
    void nullVersionReadsAsZero() {
        TenantEntity fresh = tenant(TenantStatus.PENDING);
        fresh.setVersion(null);
        publisher.tenantCreated(fresh);
        assertThat(captured().aggregateVersion()).isZero();
        verify(writer).publish(any(), any());
    }
}

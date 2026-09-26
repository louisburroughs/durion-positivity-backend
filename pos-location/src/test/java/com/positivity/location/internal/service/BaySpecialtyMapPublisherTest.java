package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1;
import com.positivity.location.internal.config.OutboxEventWriter;
import com.positivity.location.internal.entity.BaySpecialtyMapVersionEntity;
import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.enums.BayType;
import com.positivity.location.internal.repository.BaySpecialtyMapVersionRepository;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link BaySpecialtyMapPublisher}'s contract: the full map (one entry per {@link BayType}),
 * {@code publishChanged} bumps the tenant's version while {@code publishCurrent} does not, and
 * nothing is queued when the outbox writer is absent (Kafka disabled).
 */
class BaySpecialtyMapPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-26T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID TENANT_ID = UUID.fromString("01990000-0000-7000-8000-0000000000a1");

    private final BaySpecialtyOperationRepository operationRepository = mock(BaySpecialtyOperationRepository.class);
    private final BaySpecialtyMapVersionRepository versionRepository = mock(BaySpecialtyMapVersionRepository.class);
    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private BaySpecialtyMapPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new BaySpecialtyMapPublisher(
                operationRepository, versionRepository, writerProvider, TEST_CLOCK, "location.events.v1");
    }

    private BaySpecialtyOperationEntity row(String bayType, String operationCode) {
        return BaySpecialtyOperationEntity.builder()
                .bayType(bayType)
                .operationCode(operationCode)
                .build();
    }

    private BaySpecialtyMapUpdatedV1 captured() {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(BaySpecialtyMapUpdatedV1.EVENT_TYPE);
        return (BaySpecialtyMapUpdatedV1) captor.getValue().payload();
    }

    @Test
    @DisplayName("The published map has one entry per BayType, general types carrying empty codes")
    void payloadHasOneEntryPerBayType() {
        when(operationRepository.findAll())
                .thenReturn(List.of(
                        row("ALIGNMENT", "WHEEL-ALIGNMENT-4-WHEEL"),
                        row("TIRE_SERVICE", "TIRE-INSTALL-SET-4"),
                        row("TIRE_SERVICE", "WHEEL-BALANCE-SET-4")));
        when(versionRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        publisher.publishChanged(TENANT_ID);

        BaySpecialtyMapUpdatedV1 payload = captured();
        assertThat(payload.tenantId()).isEqualTo(TENANT_ID);
        assertThat(payload.entries()).hasSize(BayType.values().length);

        BaySpecialtyMapUpdatedV1.Entry alignment = entryFor(payload, "ALIGNMENT");
        assertThat(alignment.operationCodes()).containsExactly("WHEEL-ALIGNMENT-4-WHEEL");
        assertThat(alignment.acceptsGeneralWork()).isTrue();

        BaySpecialtyMapUpdatedV1.Entry tireService = entryFor(payload, "TIRE_SERVICE");
        assertThat(tireService.operationCodes()).containsExactlyInAnyOrder("TIRE-INSTALL-SET-4", "WHEEL-BALANCE-SET-4");

        BaySpecialtyMapUpdatedV1.Entry generalService = entryFor(payload, "GENERAL_SERVICE");
        assertThat(generalService.operationCodes()).isEmpty();
        assertThat(generalService.acceptsGeneralWork()).isTrue();

        BaySpecialtyMapUpdatedV1.Entry washDetail = entryFor(payload, "WASH_DETAIL");
        assertThat(washDetail.operationCodes()).isEmpty();
        assertThat(washDetail.acceptsGeneralWork()).isFalse();
    }

    @Test
    @DisplayName("publishChanged bumps a fresh tenant's version to 1")
    void publishChangedStartsAtOne() {
        when(operationRepository.findAll()).thenReturn(List.of());
        when(versionRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        publisher.publishChanged(TENANT_ID);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(1L);
        assertThat(captured().aggregateVersion()).isEqualTo(1L);

        ArgumentCaptor<BaySpecialtyMapVersionEntity> saved =
                ArgumentCaptor.forClass(BaySpecialtyMapVersionEntity.class);
        verify(versionRepository).save(saved.capture());
        assertThat(saved.getValue().getVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("publishChanged bumps an existing version by one")
    void publishChangedIncrementsExistingVersion() {
        when(operationRepository.findAll()).thenReturn(List.of());
        when(versionRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(BaySpecialtyMapVersionEntity.builder()
                        .id(UUID.randomUUID())
                        .version(4L)
                        .build()));

        publisher.publishChanged(TENANT_ID);

        assertThat(captured().aggregateVersion()).isEqualTo(5L);
    }

    @Test
    @DisplayName("publishCurrent republishes at the recorded version without bumping it")
    void publishCurrentDoesNotBump() {
        when(operationRepository.findAll()).thenReturn(List.of());
        when(versionRepository.findFirstByOrderByIdAsc())
                .thenReturn(Optional.of(BaySpecialtyMapVersionEntity.builder()
                        .id(UUID.randomUUID())
                        .version(7L)
                        .build()));

        publisher.publishCurrent(TENANT_ID);

        assertThat(captured().aggregateVersion()).isEqualTo(7L);
        verify(versionRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("publishCurrent defaults to version 1 for a tenant with no version row yet")
    void publishCurrentDefaultsToOneWithNoVersionRow() {
        when(operationRepository.findAll()).thenReturn(List.of());
        when(versionRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        publisher.publishCurrent(TENANT_ID);

        assertThat(captured().aggregateVersion()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Nothing is queued when Kafka is disabled (the outbox writer bean is absent)")
    void noWriterIsANoOp() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.publishChanged(TENANT_ID);
        publisher.publishCurrent(TENANT_ID);

        org.mockito.Mockito.verifyNoInteractions(operationRepository, versionRepository);
    }

    private BaySpecialtyMapUpdatedV1.Entry entryFor(BaySpecialtyMapUpdatedV1 payload, String bayType) {
        return payload.entries().stream()
                .filter(entry -> entry.bayType().equals(bayType))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No entry for bayType " + bayType));
    }
}

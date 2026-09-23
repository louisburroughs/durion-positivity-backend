package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * The catalog replica consumer's contract: only {@code catalog.service.updated} is applied, the
 * stale guard applies on an equal version so {@code facts/replay} repairs rather than no-ops, a
 * delete tombstone lands as {@code active = false}, and a transient DB error reaches the container
 * while a malformed payload does not poison the partition.
 */
class CatalogEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SERVICE_ID = UUID.fromString("01960022-0000-7000-8000-000000000001");

    private final ProcessedEventRepository processedEventRepository = mock(ProcessedEventRepository.class);
    private final ExtCatalogServiceReplicaRepository replicaRepository = mock(ExtCatalogServiceReplicaRepository.class);
    private CatalogEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new CatalogEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEventRepository,
                replicaRepository,
                mock(PlatformTransactionManager.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
    }

    private String serviceUpdated(String eventId, long aggregateVersion, boolean active) {
        return """
                {"eventId":"%s","eventType":"catalog.service.updated","aggregateVersion":%d,
                 "payload":{"serviceId":"%s","name":"Front brake pad replacement",
                            "operationCode":"BRAKE-PAD-FRONT","active":%b}}
                """.formatted(eventId, aggregateVersion, SERVICE_ID, active);
    }

    private ExtCatalogServiceReplica savedReplica() {
        ArgumentCaptor<ExtCatalogServiceReplica> saved = ArgumentCaptor.forClass(ExtCatalogServiceReplica.class);
        verify(replicaRepository).save(saved.capture());
        return saved.getValue();
    }

    @Test
    @DisplayName("service.updated upserts the replica row and records the eventId under the catalog owner")
    void serviceUpdatedUpsertsReplica() {
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());

        listener.onCatalogEvent(serviceUpdated("01990000-0000-7000-8000-000000000c01", 10, true));

        ExtCatalogServiceReplica replica = savedReplica();
        assertThat(replica.getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(replica.getName()).isEqualTo("Front brake pad replacement");
        assertThat(replica.getOperationCode()).isEqualTo("BRAKE-PAD-FRONT");
        assertThat(replica.isActive()).isTrue();
        assertThat(replica.getAggregateVersion()).isEqualTo(10);
        assertThat(replica.getUpdatedAt()).isEqualTo(Instant.parse("2026-09-17T12:00:00Z"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("01990000-0000-7000-8000-000000000c01");
        assertThat(processed.getValue().getOwner()).isEqualTo(CatalogEventsListener.OWNER);
        assertThat(processed.getValue().getProcessedAt()).isEqualTo(Instant.parse("2026-09-17T12:00:00Z"));
    }

    @Test
    @DisplayName("A service with no operation code replicates with a null one — general work, never a specialty")
    void serviceWithoutOperationCodeReplicatesNull() {
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());

        listener.onCatalogEvent("""
                {"eventId":"01990000-0000-7000-8000-000000000c02","eventType":"catalog.service.updated",
                 "aggregateVersion":11,
                 "payload":{"serviceId":"%s","name":"Courtesy inspection","active":true}}
                """.formatted(SERVICE_ID));

        assertThat(savedReplica().getOperationCode()).isNull();
    }

    @Test
    @DisplayName(
            "The delete tombstone keeps the row and flips active to false, so a retired code is not an unknown one")
    void deleteTombstoneDeactivatesRatherThanRemoving() {
        when(replicaRepository.findById(SERVICE_ID))
                .thenReturn(Optional.of(ExtCatalogServiceReplica.builder()
                        .serviceId(SERVICE_ID)
                        .operationCode("BRAKE-PAD-FRONT")
                        .active(true)
                        .aggregateVersion(10)
                        .build()));

        listener.onCatalogEvent(serviceUpdated("01990000-0000-7000-8000-000000000c03", 12, false));

        assertThat(savedReplica().isActive()).isFalse();
        verify(replicaRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("A fact older than the held version is discarded, but still marked processed")
    void staleFactSkippedAndStillRecorded() {
        when(replicaRepository.findById(SERVICE_ID))
                .thenReturn(Optional.of(ExtCatalogServiceReplica.builder()
                        .serviceId(SERVICE_ID)
                        .aggregateVersion(99)
                        .build()));

        listener.onCatalogEvent(serviceUpdated("01990000-0000-7000-8000-000000000c04", 10, true));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("An equal version applies rather than skipping, so facts/replay repairs a wrong row")
    void equalVersionAppliesSoReplayRepairs() {
        when(replicaRepository.findById(SERVICE_ID))
                .thenReturn(Optional.of(ExtCatalogServiceReplica.builder()
                        .serviceId(SERVICE_ID)
                        .name("stale name")
                        .operationCode(null)
                        .active(false)
                        .aggregateVersion(10)
                        .build()));

        listener.onCatalogEvent(serviceUpdated("01990000-0000-7000-8000-000000000c05", 10, true));

        ExtCatalogServiceReplica replica = savedReplica();
        assertThat(replica.getName()).isEqualTo("Front brake pad replacement");
        assertThat(replica.getOperationCode()).isEqualTo("BRAKE-PAD-FRONT");
        assertThat(replica.isActive()).isTrue();
    }

    @Test
    @DisplayName("Product facts on the same topic are skipped without a dedup row — nothing counts them")
    void otherEventTypesSkippedWithoutRecording() {
        listener.onCatalogEvent("""
                {"eventId":"01990000-0000-7000-8000-000000000c06","eventType":"catalog.product.updated",
                 "aggregateVersion":5,"payload":{"productId":"01960022-0000-7000-8000-0000000000aa"}}
                """);

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("An unparsable message is dropped rather than poisoning the partition")
    void unparsableMessageDropped() {
        listener.onCatalogEvent("not json at all");

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A service.updated with no eventId is dropped — there is nothing to dedup on")
    void missingEventIdDropped() {
        listener.onCatalogEvent("""
                {"eventType":"catalog.service.updated","aggregateVersion":7,
                 "payload":{"serviceId":"%s","operationCode":"BRAKE-PAD-FRONT","active":true}}
                """.formatted(SERVICE_ID));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A blank eventId is dropped for the same reason as a missing one")
    void blankEventIdDropped() {
        listener.onCatalogEvent(serviceUpdated("   ", 7, true));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A redelivered eventId is skipped by the processed_events guard")
    void duplicateEventSkipped() {
        when(processedEventRepository.existsById("01990000-0000-7000-8000-000000000c07"))
                .thenReturn(true);

        listener.onCatalogEvent(serviceUpdated("01990000-0000-7000-8000-000000000c07", 13, true));

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A transient DB error reaches the container for retry/DLQ, and nothing is marked processed")
    void transientDataAccessErrorRethrown() {
        when(replicaRepository.findById(SERVICE_ID)).thenReturn(Optional.empty());
        doThrow(new QueryTimeoutException("statement timeout"))
                .when(replicaRepository)
                .save(any());

        assertThatThrownBy(
                        () -> listener.onCatalogEvent(serviceUpdated("01990000-0000-7000-8000-000000000c08", 14, true)))
                .isInstanceOf(QueryTimeoutException.class);

        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("A malformed payload is logged and dropped, leaving no processed_events row to hide it")
    void malformedPayloadDroppedWithoutRecording() {
        listener.onCatalogEvent("""
                {"eventId":"01990000-0000-7000-8000-000000000c09","eventType":"catalog.service.updated",
                 "aggregateVersion":15,
                 "payload":{"serviceId":"not-a-uuid","operationCode":"BRAKE-PAD-FRONT","active":true}}
                """);

        verify(replicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }
}

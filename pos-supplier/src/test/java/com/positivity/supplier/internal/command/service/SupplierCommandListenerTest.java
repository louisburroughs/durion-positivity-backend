package com.positivity.supplier.internal.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.supplier.SupplierPriceCatalogRepublishRequestedV1;
import com.positivity.supplier.internal.entity.ProcessedEvent;
import com.positivity.supplier.internal.order.service.TransmissionIntentWriter;
import com.positivity.supplier.internal.pricecatalog.service.PriceCatalogRepublisher;
import com.positivity.supplier.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Inbound supplier commands (ADR-0049 §3, ADR-0044 §4)")
class SupplierCommandListenerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID IMPORT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e");

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private TransmissionIntentWriter intentWriter;

    @Mock
    private PriceCatalogRepublisher republisher;

    private SupplierCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new SupplierCommandListener(
                CLOCK, new ObjectMapper(), processedEventRepository, intentWriter, republisher);
    }

    private static String republishCommand(String eventId) {
        return """
                {"eventId":"%s","eventType":"supplier.pricecatalog.republish.requested","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-08-14T11:59:00Z",
                 "sourceService":"pos-catalog","source":"pos-catalog",
                 "payload":{"importManifestId":"%s","vendorProfileId":"%s","chunksApplied":1,
                   "expectedChunks":2,"requestedBy":"pos-catalog","reason":"applied 1 of 2 chunks"}}
                """.formatted(eventId, IMPORT_ID, IMPORT_ID, PROFILE_ID);
    }

    private static String orderCommand(String eventId) {
        return """
                {"eventId":"%s","eventType":"supplier.order.requested","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-08-14T11:59:00Z",
                 "sourceService":"pos-order","source":"pos-order","correlationId":"corr-1",
                 "payload":{"purchaseOrderId":"%s","revision":1,"intentType":"INITIAL",
                   "supplierRef":"michelin-eu","deliveryLocationId":null,"purchaseOrderNumber":"PO-1",
                   "lines":[{"lineNumber":1,"articleEan":"3528709999083",
                     "supplierArticleCode":"99991","quantity":4,"requestedDeliveryDate":null}]}}
                """.formatted(eventId, ORDER_ID, ORDER_ID);
    }

    private ProcessedEvent recorded() {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void servesARepublishRequestFromTheCatalogConsumer() {
        listener.onSupplierCommand(republishCommand("e-1"));

        ArgumentCaptor<SupplierPriceCatalogRepublishRequestedV1> captor =
                ArgumentCaptor.forClass(SupplierPriceCatalogRepublishRequestedV1.class);
        verify(republisher).republish(captor.capture());
        assertThat(captor.getValue().importManifestId()).isEqualTo(IMPORT_ID);
        assertThat(captor.getValue().vendorProfileId()).isEqualTo(PROFILE_ID);
        assertThat(captor.getValue().chunksApplied()).isEqualTo(1);
        assertThat(captor.getValue().expectedChunks()).isEqualTo(2);
    }

    @Test
    void mintsATransmissionIntentForAnOrderCommand() {
        listener.onSupplierCommand(orderCommand("e-2"));

        verify(intentWriter).mint(any(), anyString());
        verifyNoInteractions(republisher);
    }

    @Test
    void bothCommandTypesReachTheirHandlerThroughOneConsumer() {
        // The point of a single consumer group on this topic. With one group per command type, the
        // group that reached an event first would record its id in processed_events — which is
        // keyed by event id alone — and the group that actually handles that command would find the
        // id present and skip the work. Purchase orders and recoveries would vanish intermittently.
        listener.onSupplierCommand(orderCommand("e-3"));
        listener.onSupplierCommand(republishCommand("e-4"));

        verify(intentWriter).mint(any(), anyString());
        verify(republisher).republish(any());
    }

    @Test
    void recordsTheProducingDomainSoAManifestScanReconcilesAgainstTheRightProducer() {
        listener.onSupplierCommand(republishCommand("e-5"));

        assertThat(recorded().getOwner()).isEqualTo(SupplierCommandListener.CATALOG_OWNER);
        assertThat(recorded().getEventId()).isEqualTo("e-5");
    }

    @Test
    void recordsAnOrderCommandAgainstTheOrderDomain() {
        listener.onSupplierCommand(orderCommand("e-6"));

        assertThat(recorded().getOwner()).isEqualTo(SupplierCommandListener.ORDER_OWNER);
    }

    @Test
    void skipsARedeliveredCommandWithoutRepeatingTheWork() {
        when(processedEventRepository.existsById("e-7")).thenReturn(true);

        listener.onSupplierCommand(republishCommand("e-7"));

        verifyNoInteractions(republisher);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void recordsACommandItDoesNotHandleSoTheProducerSeesItAsDeliveredRatherThanMissing() {
        String unknown = """
                {"eventId":"e-8","eventType":"supplier.something.else","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"occurredAtUtc":"2026-08-14T11:59:00Z",
                 "source":"pos-inventory","payload":{}}
                """.formatted(IMPORT_ID);

        listener.onSupplierCommand(unknown);

        verifyNoInteractions(republisher, intentWriter);
        assertThat(recorded().getOwner()).isEqualTo("inventory");
    }

    @Test
    void rethrowsTransientDatabaseTroubleSoTheContainerRetries() {
        when(republisher.republish(any())).thenThrow(new QueryTimeoutException("statement timeout"));

        // Recording this as processed would lose the recovery permanently: the consumer's gap would
        // stay, and the request that would have healed it is gone.
        assertThatThrownBy(() -> listener.onSupplierCommand(republishCommand("e-9")))
                .isInstanceOf(QueryTimeoutException.class);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void doesNotPassOffThisModulesOwnInconsistentStateAsAMalformedCommand() {
        when(republisher.republish(any())).thenThrow(new IllegalStateException("chunk 2 has no staged lines"));

        // The command is valid; our staged data contradicts itself. Recording it as processed would
        // blame the producer for our bug and would mark as handled a command whose transaction the
        // failure has already doomed.
        assertThatThrownBy(() -> listener.onSupplierCommand(republishCommand("e-11")))
                .isInstanceOf(IllegalStateException.class);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void recordsAMalformedCommandRatherThanBlockingThePartitionOnIt() {
        String malformed = """
                {"eventId":"e-10","eventType":"supplier.pricecatalog.republish.requested","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"source":"pos-catalog","payload":{"nonsense":true}}
                """.formatted(IMPORT_ID);

        listener.onSupplierCommand(malformed);

        verifyNoInteractions(republisher);
        assertThat(recorded().getEventId()).isEqualTo("e-10");
    }

    @Test
    void ignoresACommandWithoutAnEventIdBecauseItCannotBeDeduplicated() {
        listener.onSupplierCommand("""
                {"eventType":"supplier.pricecatalog.republish.requested","payload":{}}
                """);

        verifyNoInteractions(republisher);
        verify(processedEventRepository, never()).save(any());
    }
}

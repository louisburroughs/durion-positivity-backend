package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.inventory.BackorderCreatedV1;
import com.positivity.domainevents.inventory.BackorderResolvedV1;
import com.positivity.domainevents.inventory.ConsumptionRecordedV1;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.LotExpiryAlertV1;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.domainevents.inventory.ReservationOutcomeV1;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.domainevents.inventory.TransferOrderUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for {@link InventoryFactPublisher} (ADR-0044 §6, #899): per-transaction dedupe and
 * beforeCommit snapshot emission for availability and storage-location on-hand facts.
 */
class InventoryFactPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final InventoryAvailabilityService availabilityService = mock(InventoryAvailabilityService.class);
    private final LocationInventoryInquiryService inquiryService = mock(LocationInventoryInquiryService.class);
    private final InventoryLeadTimeService leadTimeService = mock(InventoryLeadTimeService.class);
    private final com.positivity.inventory.internal.repository.PickListRepository pickListRepository =
            mock(com.positivity.inventory.internal.repository.PickListRepository.class);
    private final com.positivity.inventory.internal.repository.PickTaskRepository pickTaskRepository =
            mock(com.positivity.inventory.internal.repository.PickTaskRepository.class);

    private InventoryFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new InventoryFactPublisher(
                writerProvider,
                availabilityService,
                inquiryService,
                leadTimeService,
                pickListRepository,
                pickTaskRepository,
                TEST_CLOCK);
        ReflectionTestUtils.setField(publisher, "eventsTopic", "inventory.events.v1");
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.unbindResourceIfPossible(InventoryFactPublisher.class);
    }

    private void fireBeforeCommit() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.beforeCommit(false);
        }
    }

    @Test
    @DisplayName("Repeated ledger marks emit one availability snapshot and one on-hand snapshot")
    void dedupesWithinTransaction() {
        UUID locationId = UUID.randomUUID();
        InventoryLedgerEntry entry = InventoryLedgerEntry.builder()
                .stockItemId("SKU-1")
                .locationId(locationId)
                .build();
        when(availabilityService.queryAvailability("SKU-1", locationId, null, null))
                .thenReturn(AvailabilityView.builder()
                        .productSku("SKU-1")
                        .locationId(locationId)
                        .onHandQuantity(new BigDecimal("7"))
                        .allocatedQuantity(new BigDecimal("2"))
                        .availableToPromiseQuantity(new BigDecimal("5"))
                        .unitOfMeasure("EACH")
                        .incomingQty(new BigDecimal("4"))
                        .outgoingQty(new BigDecimal("1"))
                        .projectedAvailable(new BigDecimal("10"))
                        .build());
        when(inquiryService.getLocationInventory(locationId, null))
                .thenReturn(LocationInventoryInquiryResponse.builder()
                        .locationId(locationId)
                        .onHandQuantity(new BigDecimal("7"))
                        .availableToPromiseQuantity(new BigDecimal("5"))
                        .build());

        publisher.markEntry(entry);
        publisher.markEntry(entry);
        fireBeforeCommit();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<Object>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, times(2)).publish(eq("inventory.events.v1"), captor.capture());
        List<DomainEventEnvelope<Object>> envelopes = captor.getAllValues();
        assertThat(envelopes.get(0).eventType()).isEqualTo(InventoryAvailabilityUpdatedV1.EVENT_TYPE);
        assertThat(envelopes.get(0).schemaVersion()).isEqualTo(InventoryAvailabilityUpdatedV1.SCHEMA_VERSION);
        InventoryAvailabilityUpdatedV1 availability =
                (InventoryAvailabilityUpdatedV1) envelopes.get(0).payload();
        assertThat(availability.onHandQuantity()).isEqualByComparingTo("7");
        assertThat(availability.availableToPromiseQuantity()).isEqualByComparingTo("5");
        // Schema v2 forecast fields (odoo-parity A2, #1028) carry the unbounded view values.
        assertThat(availability.incomingQuantity()).isEqualByComparingTo("4");
        assertThat(availability.outgoingQuantity()).isEqualByComparingTo("1");
        assertThat(availability.projectedAvailableQuantity()).isEqualByComparingTo("10");
        assertThat(envelopes.get(1).eventType()).isEqualTo(StorageLocationOnHandUpdatedV1.EVENT_TYPE);
        assertThat(((StorageLocationOnHandUpdatedV1) envelopes.get(1).payload()).onHandQuantity())
                .isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("Recorded expected-supply-dropped facts are written to the outbox at beforeCommit (#1028)")
    void publishesExpectedSupplyDroppedFact() {
        UUID poId = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1 fact =
                new com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1(
                        "SKU-9",
                        siteId,
                        new java.math.BigDecimal("5.5"),
                        poId,
                        com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1.REASON_CANCELLED,
                        Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordExpectedSupplyDropped(fact);
        fireBeforeCommit();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<Object>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("inventory.events.v1"), captor.capture());
        DomainEventEnvelope<Object> envelope = captor.getValue();
        assertThat(envelope.eventType())
                .isEqualTo(com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1.EVENT_TYPE);
        assertThat(envelope.schemaVersion())
                .isEqualTo(com.positivity.domainevents.inventory.ExpectedSupplyDroppedV1.SCHEMA_VERSION);
        assertThat(envelope.aggregateId()).isEqualTo(poId);
        assertThat(envelope.payload()).isSameAs(fact);
    }

    @Test
    @DisplayName("No-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.markLedgerChanged("SKU-1", UUID.randomUUID());
        fireBeforeCommit();

        verify(writer, never()).publish(any(), any());
    }

    // ── Fact→event characterisation (Phase 3.6) ────────────────────────────────────
    //
    // Written before publishPending was split into per-fact emitters. That method scored 52
    // cognitive complexity and sat at 47.5% branch coverage: of the fourteen fact types it
    // emits, only expected-supply-dropped had a publish test. Every one of these events is
    // consumed by other modules, so the mapping each block performs — which event type, which
    // schema version, which aggregate id — is a contract, and it was almost entirely unpinned.
    //
    // Each test seeds one pending fact and asserts the envelope it produces. Together they are
    // the evidence that the split preserved every mapping.

    @Test
    @DisplayName("A recorded consumption is published keyed on its consumption id")
    void publishesConsumptionFact() {
        UUID consumptionId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        ConsumptionRecordedV1 fact = new ConsumptionRecordedV1(
                consumptionId,
                UUID.fromString("00000000-0000-0000-0000-0000000000c2"),
                null,
                2,
                Instant.parse("2026-07-13T12:00:00Z"),
                List.of(),
                "STANDARD");

        publisher.recordConsumption(fact);
        fireBeforeCommit();

        assertPublished(ConsumptionRecordedV1.EVENT_TYPE, ConsumptionRecordedV1.SCHEMA_VERSION, consumptionId, fact);
    }

    @Test
    @DisplayName("A recorded scrap posting is published keyed on its scrap id")
    void publishesScrapPostedFact() {
        UUID scrapId = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
        ScrapPostedV1 fact = new ScrapPostedV1(
                scrapId,
                "SKU-SCRAP",
                UUID.fromString("00000000-0000-0000-0000-0000000000d2"),
                null,
                3,
                "DAMAGED",
                new BigDecimal("4.25"),
                "STANDARD",
                null,
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordScrapPosted(fact);
        fireBeforeCommit();

        assertPublished(ScrapPostedV1.EVENT_TYPE, ScrapPostedV1.SCHEMA_VERSION, scrapId, fact);
    }

    @Test
    @DisplayName("A product revaluation is published keyed on its revaluation id")
    void publishesProductValueChangedFact() {
        UUID revaluationId = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        ProductValueChangedV1 fact = new ProductValueChangedV1(
                revaluationId,
                "SKU-VAL",
                "WEIGHTED_AVERAGE",
                new BigDecimal("10.00"),
                new BigDecimal("12.50"),
                new BigDecimal("40"),
                new BigDecimal("100.00"),
                "MARKET_ADJUSTMENT",
                "system",
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordProductValueChanged(fact);
        fireBeforeCommit();

        assertPublished(ProductValueChangedV1.EVENT_TYPE, ProductValueChangedV1.SCHEMA_VERSION, revaluationId, fact);
    }

    @Test
    @DisplayName("A transfer-order update is published keyed on its transfer-order id")
    void publishesTransferOrderUpdatedFact() {
        UUID transferOrderId = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
        TransferOrderUpdatedV1 fact = new TransferOrderUpdatedV1(
                transferOrderId,
                "IN_TRANSIT",
                UUID.fromString("00000000-0000-0000-0000-0000000000f2"),
                null,
                UUID.fromString("00000000-0000-0000-0000-0000000000f3"),
                null,
                List.of(new TransferOrderUpdatedV1.LineSummary("SKU-XFER", 4, 4, 0)),
                null,
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordTransferOrderUpdated(fact);
        fireBeforeCommit();

        assertPublished(
                TransferOrderUpdatedV1.EVENT_TYPE, TransferOrderUpdatedV1.SCHEMA_VERSION, transferOrderId, fact);
    }

    @Test
    @DisplayName("A created backorder is published keyed on its backorder id")
    void publishesBackorderCreatedFact() {
        UUID backorderId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        BackorderCreatedV1 fact = new BackorderCreatedV1(
                backorderId,
                UUID.fromString("00000000-0000-0000-0000-000000000103"),
                null,
                "SKU-SHORT",
                new BigDecimal("2"),
                UUID.fromString("00000000-0000-0000-0000-000000000102"),
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordBackorderCreated(fact);
        fireBeforeCommit();

        assertPublished(BackorderCreatedV1.EVENT_TYPE, BackorderCreatedV1.SCHEMA_VERSION, backorderId, fact);
    }

    @Test
    @DisplayName("A resolved backorder is published keyed on the same backorder id as its creation")
    void publishesBackorderResolvedFact() {
        UUID backorderId = UUID.fromString("00000000-0000-0000-0000-000000000111");
        BackorderResolvedV1 fact = new BackorderResolvedV1(
                backorderId,
                UUID.fromString("00000000-0000-0000-0000-000000000112"),
                null,
                "SKU-SHORT",
                new BigDecimal("2"),
                "RECEIPT",
                null,
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordBackorderResolved(fact);
        fireBeforeCommit();

        // Same aggregate id as the creation fact above: created and resolved must land on one
        // partition in order, or a consumer can see the resolution before the creation.
        assertPublished(BackorderResolvedV1.EVENT_TYPE, BackorderResolvedV1.SCHEMA_VERSION, backorderId, fact);
    }

    @Test
    @DisplayName("A reservation outcome is published keyed on its reservation id")
    void publishesReservationOutcomeFact() {
        UUID reservationId = UUID.fromString("00000000-0000-0000-0000-000000000121");
        ReservationOutcomeV1 fact = new ReservationOutcomeV1(
                reservationId,
                UUID.fromString("00000000-0000-0000-0000-000000000123"),
                null,
                "SKU-RES",
                new BigDecimal("1"),
                false,
                UUID.fromString("00000000-0000-0000-0000-000000000122"),
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordReservationOutcome(fact);
        fireBeforeCommit();

        assertPublished(ReservationOutcomeV1.EVENT_TYPE, ReservationOutcomeV1.SCHEMA_VERSION, reservationId, fact);
    }

    @Test
    @DisplayName("A lot-expiry alert is published keyed on its lot id")
    void publishesLotExpiryAlertFact() {
        UUID lotId = UUID.fromString("00000000-0000-0000-0000-000000000131");
        LotExpiryAlertV1 fact = new LotExpiryAlertV1(
                lotId,
                "SKU-LOT",
                "LOT-42",
                LocalDate.parse("2026-09-01"),
                "EXPIRING_SOON",
                Instant.parse("2026-07-13T12:00:00Z"));

        publisher.recordLotExpiryAlert(fact);
        fireBeforeCommit();

        assertPublished(LotExpiryAlertV1.EVENT_TYPE, LotExpiryAlertV1.SCHEMA_VERSION, lotId, fact);
    }

    @Test
    @DisplayName("Facts of different kinds recorded in one transaction all reach the outbox")
    void publishesEveryPendingFactKindInOneTransaction() {
        UUID scrapId = UUID.fromString("00000000-0000-0000-0000-000000000141");
        UUID lotId = UUID.fromString("00000000-0000-0000-0000-000000000142");
        publisher.recordScrapPosted(new ScrapPostedV1(
                scrapId,
                "SKU-A",
                null,
                null,
                1,
                "DAMAGED",
                null,
                "STANDARD",
                null,
                Instant.parse("2026-07-13T12:00:00Z")));
        publisher.recordLotExpiryAlert(
                new LotExpiryAlertV1(lotId, "SKU-B", "LOT-1", null, "EXPIRED", Instant.parse("2026-07-13T12:00:00Z")));

        fireBeforeCommit();

        // The emitters are independent: one kind being pending must not suppress another.
        verify(writer, times(2)).publish(eq("inventory.events.v1"), any());
    }

    private void assertPublished(String eventType, int schemaVersion, UUID aggregateId, Object fact) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<Object>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("inventory.events.v1"), captor.capture());
        DomainEventEnvelope<Object> envelope = captor.getValue();
        assertThat(envelope.eventType()).isEqualTo(eventType);
        assertThat(envelope.schemaVersion()).isEqualTo(schemaVersion);
        assertThat(envelope.aggregateId()).isEqualTo(aggregateId);
        assertThat(envelope.payload()).isSameAs(fact);
    }
}

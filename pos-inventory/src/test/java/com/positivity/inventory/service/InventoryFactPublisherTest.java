package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.inventory.internal.config.OutboxEventWriter;
import com.positivity.inventory.internal.dto.AvailabilityView;
import com.positivity.inventory.internal.dto.LocationInventoryInquiryResponse;
import com.positivity.inventory.internal.entity.InventoryLedgerEntry;
import com.positivity.inventory.internal.service.InventoryFactPublisher;
import com.positivity.inventory.service.InventoryAvailabilityService;
import com.positivity.inventory.service.InventoryLeadTimeService;
import com.positivity.inventory.service.LocationInventoryInquiryService;
import java.time.Clock;
import java.time.Instant;
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

    private InventoryFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new InventoryFactPublisher(
                writerProvider, availabilityService, inquiryService, leadTimeService, TEST_CLOCK);
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
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
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
                        .onHandQuantity(7)
                        .allocatedQuantity(2)
                        .availableToPromiseQuantity(5)
                        .unitOfMeasure("EACH")
                        .build());
        when(inquiryService.getLocationInventory(locationId, null))
                .thenReturn(LocationInventoryInquiryResponse.builder()
                        .locationId(locationId)
                        .onHandQuantity(7)
                        .availableToPromiseQuantity(5)
                        .build());

        publisher.markEntry(entry);
        publisher.markEntry(entry);
        fireBeforeCommit();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<DomainEventEnvelope<Object>> captor =
                ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, times(2)).publish(eq("inventory.events.v1"), captor.capture());
        List<DomainEventEnvelope<Object>> envelopes = captor.getAllValues();
        assertThat(envelopes.get(0).eventType()).isEqualTo(InventoryAvailabilityUpdatedV1.EVENT_TYPE);
        InventoryAvailabilityUpdatedV1 availability =
                (InventoryAvailabilityUpdatedV1) envelopes.get(0).payload();
        assertThat(availability.onHandQuantity()).isEqualTo(7);
        assertThat(availability.availableToPromiseQuantity()).isEqualTo(5);
        assertThat(envelopes.get(1).eventType()).isEqualTo(StorageLocationOnHandUpdatedV1.EVENT_TYPE);
        assertThat(((StorageLocationOnHandUpdatedV1) envelopes.get(1).payload()).onHandQuantity())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("No-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.markLedgerChanged("SKU-1", UUID.randomUUID());
        fireBeforeCommit();

        verify(writer, never()).publish(any(), any());
    }
}

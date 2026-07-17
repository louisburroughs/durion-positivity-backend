package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.entity.WarrantyPartReturnHold;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.inventory.internal.repository.WarrantyPartReturnHoldRepository;
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
import tools.jackson.databind.ObjectMapper;

class WarrantyEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID CLAIM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CLAIM_LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PART_RETURN_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID PRODUCT_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final WarrantyPartReturnHoldRepository holds = mock(WarrantyPartReturnHoldRepository.class);

    private WarrantyEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WarrantyEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, holds);
    }

    private String requested(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"warranty.part-return.requested","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"claimId":"%s","claimLineId":"%s","partReturnId":"%s",
                            "productEntityId":"%s","serialNumber":"SN-9","disposition":"RETURN_TO_VENDOR",
                            "requestedAt":"2026-07-15T09:00:00Z"}}
                """.formatted(eventId, CLAIM_ID, version, CLAIM_ID, CLAIM_LINE_ID, PART_RETURN_ID, PRODUCT_ENTITY_ID);
    }

    private String shipped(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"warranty.part-return.shipped","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"claimId":"%s","claimLineId":"%s","partReturnId":"%s",
                            "carrier":"UPS","trackingNumber":"1Z999","shippedAt":"2026-07-16T10:00:00Z"}}
                """.formatted(eventId, CLAIM_ID, version, CLAIM_ID, CLAIM_LINE_ID, PART_RETURN_ID);
    }

    private WarrantyPartReturnHold requestedRow(long version) {
        return WarrantyPartReturnHold.builder()
                .partReturnId(PART_RETURN_ID)
                .claimId(CLAIM_ID)
                .claimLineId(CLAIM_LINE_ID)
                .productEntityId(PRODUCT_ENTITY_ID)
                .serialNumber("SN-9")
                .disposition("RETURN_TO_VENDOR")
                .status(WarrantyPartReturnHold.STATUS_REQUESTED)
                .requestedAt(Instant.parse("2026-07-15T09:00:00Z"))
                .aggregateVersion(version)
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
    }

    @Test
    @DisplayName("Requested fact creates a REQUESTED hold record and records the eventId")
    void requestedCreatesHold() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(holds.findById(PART_RETURN_ID)).thenReturn(Optional.empty());

        listener.onWarrantyEvent(requested("e-1", 3));

        ArgumentCaptor<WarrantyPartReturnHold> saved = ArgumentCaptor.forClass(WarrantyPartReturnHold.class);
        verify(holds).save(saved.capture());
        assertThat(saved.getValue().getPartReturnId()).isEqualTo(PART_RETURN_ID);
        assertThat(saved.getValue().getClaimId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getValue().getClaimLineId()).isEqualTo(CLAIM_LINE_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("REQUESTED");
        assertThat(saved.getValue().getSerialNumber()).isEqualTo("SN-9");
        assertThat(saved.getValue().getDisposition()).isEqualTo("RETURN_TO_VENDOR");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(3L);

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getOwner()).isEqualTo("warranty");
    }

    @Test
    @DisplayName("Shipped fact updates the hold to SHIPPED with carrier and tracking")
    void shippedUpdatesHold() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(holds.findById(PART_RETURN_ID)).thenReturn(Optional.of(requestedRow(3)));

        listener.onWarrantyEvent(shipped("e-2", 5));

        ArgumentCaptor<WarrantyPartReturnHold> saved = ArgumentCaptor.forClass(WarrantyPartReturnHold.class);
        verify(holds).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("SHIPPED");
        assertThat(saved.getValue().getCarrier()).isEqualTo("UPS");
        assertThat(saved.getValue().getTrackingNumber()).isEqualTo("1Z999");
        assertThat(saved.getValue().getShippedAt()).isEqualTo(Instant.parse("2026-07-16T10:00:00Z"));
        // Data from the requested fact is preserved.
        assertThat(saved.getValue().getDisposition()).isEqualTo("RETURN_TO_VENDOR");
        assertThat(saved.getValue().getRequestedAt()).isEqualTo(Instant.parse("2026-07-15T09:00:00Z"));
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Shipped fact without a prior row creates one from the shipment data")
    void shippedWithoutRequestedCreatesRow() {
        when(processedEvents.existsById("e-3")).thenReturn(false);
        when(holds.findById(PART_RETURN_ID)).thenReturn(Optional.empty());

        listener.onWarrantyEvent(shipped("e-3", 5));

        ArgumentCaptor<WarrantyPartReturnHold> saved = ArgumentCaptor.forClass(WarrantyPartReturnHold.class);
        verify(holds).save(saved.capture());
        assertThat(saved.getValue().getPartReturnId()).isEqualTo(PART_RETURN_ID);
        assertThat(saved.getValue().getClaimId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("SHIPPED");
        assertThat(saved.getValue().getDisposition()).isNull();
        assertThat(saved.getValue().getRequestedAt()).isNull();
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onWarrantyEvent(requested("e-dup", 3));

        verify(holds, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips stale events whose aggregateVersion is below the row's")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-old")).thenReturn(false);
        when(holds.findById(PART_RETURN_ID)).thenReturn(Optional.of(requestedRow(9)));

        listener.onWarrantyEvent(shipped("e-old", 5));

        verify(holds, never()).save(any());
        // Still recorded as processed so redelivery does not reprocess it.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Other warranty event types are recorded as processed and skipped")
    void recordsAndSkipsOtherEventTypes() {
        when(processedEvents.existsById("e-4")).thenReturn(false);

        listener.onWarrantyEvent("""
                {"eventId":"e-4","eventType":"warranty.claim.snapshot","aggregateVersion":4,"payload":{}}
                """);

        verify(holds, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-5")).thenReturn(false);
        when(holds.findById(PART_RETURN_ID)).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onWarrantyEvent(requested("e-5", 1)));

        verify(processedEvents, never()).save(any());
    }
}

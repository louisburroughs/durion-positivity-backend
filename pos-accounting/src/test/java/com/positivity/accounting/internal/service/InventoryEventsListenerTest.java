package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer-side contract test for {@code inventory.scrap.posted} ingestion (odoo-parity D2, issue
 * #1043). The envelope JSON literals pin the {@link ScrapPostedV1} fact schema — including the
 * ADR-0048 nullable-cost variant ({@code unitCost: null}, {@code costSource: NONE}) — so a
 * producer-side field rename or type change fails here, not in production.
 */
class InventoryEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-22T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SCRAP_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID STORAGE_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final InventoryShrinkagePostingService postingService = mock(InventoryShrinkagePostingService.class);

    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, postingService);
    }

    /** Representative costed scrap fact as published by pos-inventory (Wave-2 D1, #1030). */
    private String costedScrap(String eventId) {
        return """
                {"eventId":"%s","eventType":"inventory.scrap.posted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-21T09:15:00Z","sourceService":"pos-inventory",
                 "payload":{"scrapId":"%s","sku":"BRAKE-PAD-22","locationId":"%s",
                            "storageLocationId":"%s","quantity":3,"reasonCode":"DAMAGED",
                            "unitCost":12.50,"costSource":"LATEST_RECEIPT","workorderId":"%s",
                            "occurredAt":"2026-07-21T09:15:00Z"}}
                """.formatted(eventId, SCRAP_ID, SCRAP_ID, LOCATION_ID, STORAGE_LOCATION_ID, WORKORDER_ID);
    }

    /** ADR-0048 interim uncosted variant: unitCost null, costSource NONE, nullable ids absent. */
    private String uncostedScrap(String eventId) {
        return """
                {"eventId":"%s","eventType":"inventory.scrap.posted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-21T09:15:00Z","sourceService":"pos-inventory",
                 "payload":{"scrapId":"%s","sku":"BRAKE-PAD-22","locationId":null,
                            "storageLocationId":null,"quantity":3,"reasonCode":"LOST",
                            "unitCost":null,"costSource":"NONE","workorderId":null,
                            "occurredAt":"2026-07-21T09:15:00Z"}}
                """.formatted(eventId, SCRAP_ID, SCRAP_ID);
    }

    @Test
    @DisplayName("Costed scrap fact deserializes per the pinned schema and is handed to posting")
    void costedScrapPostsShrinkage() {
        when(processedEvents.existsById("e-1")).thenReturn(false);

        listener.onInventoryEvent(costedScrap("e-1"));

        ArgumentCaptor<ScrapPostedV1> fact = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(postingService).postShrinkage(fact.capture());
        assertThat(fact.getValue().scrapId()).isEqualTo(SCRAP_ID);
        assertThat(fact.getValue().sku()).isEqualTo("BRAKE-PAD-22");
        assertThat(fact.getValue().locationId()).isEqualTo(LOCATION_ID);
        assertThat(fact.getValue().storageLocationId()).isEqualTo(STORAGE_LOCATION_ID);
        assertThat(fact.getValue().quantity()).isEqualTo(3);
        assertThat(fact.getValue().reasonCode()).isEqualTo("DAMAGED");
        assertThat(fact.getValue().unitCost()).isEqualByComparingTo(new BigDecimal("12.50"));
        assertThat(fact.getValue().costSource()).isEqualTo("LATEST_RECEIPT");
        assertThat(fact.getValue().workorderId()).isEqualTo(WORKORDER_ID);
        assertThat(fact.getValue().occurredAt()).isEqualTo(Instant.parse("2026-07-21T09:15:00Z"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-1");
        assertThat(processed.getValue().getProcessedAt()).isEqualTo(Instant.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("Unknown reason codes do not block posting — they ride into the fact untouched")
    void unknownReasonCodeStillPosts() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        String message = costedScrap("e-2").replace("\"DAMAGED\"", "\"GREMLINS\"");

        listener.onInventoryEvent(message);

        ArgumentCaptor<ScrapPostedV1> fact = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(postingService).postShrinkage(fact.capture());
        assertThat(fact.getValue().reasonCode()).isEqualTo("GREMLINS");
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Uncosted fact (unitCost null, costSource NONE) is skipped but marked processed")
    void uncostedScrapIsSkippedAndMarkedProcessed() {
        when(processedEvents.existsById("e-3")).thenReturn(false);

        listener.onInventoryEvent(uncostedScrap("e-3"));

        verify(postingService, never()).postShrinkage(any());
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-3");
    }

    @Test
    @DisplayName("Zero unit cost is skipped like a null cost — no zero-value journal entry")
    void zeroCostScrapIsSkippedAndMarkedProcessed() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        String message = costedScrap("e-4").replace("12.50", "0.00");

        listener.onInventoryEvent(message);

        verify(postingService, never()).postShrinkage(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Duplicate eventId is skipped without touching posting")
    void duplicateEventIdSkipped() {
        when(processedEvents.existsById("e-5")).thenReturn(true);

        listener.onInventoryEvent(costedScrap("e-5"));

        verifyNoInteractions(postingService);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Other inventory fact types are ignored without recording their eventIds")
    void otherEventTypesIgnored() {
        listener.onInventoryEvent("""
                {"eventId":"e-6","eventType":"inventory.availability.changed","payload":{}}
                """);

        verifyNoInteractions(postingService);
        verifyNoInteractions(processedEvents);
    }

    @Test
    @DisplayName("Malformed payload is skipped but marked processed so the partition is not poisoned")
    void malformedPayloadSkippedAndMarkedProcessed() {
        when(processedEvents.existsById("e-7")).thenReturn(false);
        // quantity <= 0 violates the ScrapPostedV1 compact-constructor contract.
        String message = costedScrap("e-7").replace("\"quantity\":3", "\"quantity\":0");

        listener.onInventoryEvent(message);

        verify(postingService, never()).postShrinkage(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Unparsable message is skipped without recording anything")
    void unparsableMessageSkipped() {
        listener.onInventoryEvent("this is not json");

        verifyNoInteractions(postingService);
        verifyNoInteractions(processedEvents);
    }

    @Test
    @DisplayName("Posting failures propagate unwrapped for container retry / DLQ; nothing marked processed")
    void postingFailurePropagates() {
        when(processedEvents.existsById("e-8")).thenReturn(false);
        doThrow(new QueryTimeoutException("db down")).when(postingService).postShrinkage(any());

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onInventoryEvent(costedScrap("e-8")));

        verify(processedEvents, never()).save(any());
    }
}

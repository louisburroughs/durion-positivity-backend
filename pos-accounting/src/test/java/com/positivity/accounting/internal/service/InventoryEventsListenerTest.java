package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
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
    private final InventoryAdjustmentPostingService adjustmentPostingService =
            mock(InventoryAdjustmentPostingService.class);
    private final InventoryRevaluationPostingService revaluationPostingService =
            mock(InventoryRevaluationPostingService.class);
    private final InventoryFactIngestionRecorder ingestionRecorder = mock(InventoryFactIngestionRecorder.class);

    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                postingService,
                adjustmentPostingService,
                revaluationPostingService,
                ingestionRecorder,
                org.mockito.Mockito.mock(ObjectProvider.class),
                mock(PlatformTransactionManager.class));
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

    /**
     * odoo-parity J3 (#1053) engine-costed variant: schemaVersion 2, {@code costSource} labeled
     * with the resolved costing method (AVERAGE/STANDARD), {@code unitCost} the engine's
     * method-derived cost. The field shape is unchanged from v1 — the consumer reads it the same
     * way and posts a costed shrinkage JE.
     */
    private String engineCostedScrapV2(String eventId, String costSource, String unitCost) {
        return """
                {"eventId":"%s","eventType":"inventory.scrap.posted","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-21T09:15:00Z","sourceService":"pos-inventory",
                 "payload":{"scrapId":"%s","sku":"BRAKE-PAD-22","locationId":"%s",
                            "storageLocationId":"%s","quantity":3,"reasonCode":"DAMAGED",
                            "unitCost":%s,"costSource":"%s","workorderId":"%s",
                            "occurredAt":"2026-07-21T09:15:00Z"}}
                """.formatted(
                eventId, SCRAP_ID, SCRAP_ID, LOCATION_ID, STORAGE_LOCATION_ID, unitCost, costSource, WORKORDER_ID);
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
    @DisplayName("J3: engine-costed v2 scrap (schemaVersion 2, method costSource) posts a costed shrinkage JE")
    void engineCostedV2ScrapPostsShrinkage() {
        when(processedEvents.existsById("e-1a")).thenReturn(false);
        when(processedEvents.existsById("e-1b")).thenReturn(false);

        listener.onInventoryEvent(engineCostedScrapV2("e-1a", "AVERAGE", "4.25"));
        listener.onInventoryEvent(engineCostedScrapV2("e-1b", "STANDARD", "6.00"));

        ArgumentCaptor<ScrapPostedV1> fact = ArgumentCaptor.forClass(ScrapPostedV1.class);
        verify(postingService, org.mockito.Mockito.times(2)).postShrinkage(fact.capture());
        assertThat(fact.getAllValues().get(0).costSource()).isEqualTo("AVERAGE");
        assertThat(fact.getAllValues().get(0).unitCost()).isEqualByComparingTo(new BigDecimal("4.25"));
        assertThat(fact.getAllValues().get(1).costSource()).isEqualTo("STANDARD");
        assertThat(fact.getAllValues().get(1).unitCost()).isEqualByComparingTo(new BigDecimal("6.00"));
        verify(processedEvents, org.mockito.Mockito.times(2)).save(any());
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
    // ===== inventory.adjustment.posted (#2191) =====

    private static final UUID ADJUSTMENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID LEDGER_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID TASK_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

    /** Representative adjustment fact as pinned by {@link InventoryAdjustedV1} (#2190). */
    private String adjustment(String eventId, String quantityDelta, String unitCost, String costSource) {
        return """
                {"eventId":"%s","eventType":"inventory.adjustment.posted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-21T09:15:00Z","sourceService":"pos-inventory",
                 "payload":{"adjustmentId":"%s","adjustmentKind":"CYCLE_COUNT",
                            "ledgerEventType":"COUNT_VARIANCE_OUT","ledgerEntryId":"%s",
                            "sku":"BRAKE-PAD-22","locationId":"%s","taskId":"%s","reasonCode":"COUNT_ERROR",
                            "quantityDelta":%s,"unitCost":%s,"costSource":"%s",
                            "occurredAt":"2026-07-21T09:15:00Z"}}
                """.formatted(
                        eventId,
                        ADJUSTMENT_ID,
                        ADJUSTMENT_ID,
                        LEDGER_ENTRY_ID,
                        LOCATION_ID,
                        TASK_ID,
                        quantityDelta,
                        unitCost,
                        costSource);
    }

    @Test
    @DisplayName("Adjustment fact deserializes per the pinned schema, posts, and is recorded NEW with its entry")
    void costedAdjustmentPostsAndRecords() {
        UUID journalEntryId = UUID.randomUUID();
        when(processedEvents.existsById("a-1")).thenReturn(false);
        when(adjustmentPostingService.postAdjustment(any())).thenReturn(journalEntryId);

        listener.onInventoryEvent(adjustment("a-1", "-4", "7.25", "AVERAGE"));

        ArgumentCaptor<InventoryAdjustedV1> fact = ArgumentCaptor.forClass(InventoryAdjustedV1.class);
        verify(adjustmentPostingService).postAdjustment(fact.capture());
        assertThat(fact.getValue().adjustmentId()).isEqualTo(ADJUSTMENT_ID);
        assertThat(fact.getValue().adjustmentKind()).isEqualTo("CYCLE_COUNT");
        assertThat(fact.getValue().ledgerEventType()).isEqualTo("COUNT_VARIANCE_OUT");
        assertThat(fact.getValue().ledgerEntryId()).isEqualTo(LEDGER_ENTRY_ID);
        assertThat(fact.getValue().locationId()).isEqualTo(LOCATION_ID);
        assertThat(fact.getValue().taskId()).isEqualTo(TASK_ID);
        assertThat(fact.getValue().quantityDelta()).isEqualByComparingTo("-4");
        assertThat(fact.getValue().unitCost()).isEqualByComparingTo("7.25");
        assertThat(fact.getValue().costSource()).isEqualTo("AVERAGE");
        verify(ingestionRecorder)
                .recordPosted(
                        eq(InventoryAdjustedV1.EVENT_TYPE),
                        eq("a-1"),
                        eq(ADJUSTMENT_ID),
                        eq(java.time.LocalDateTime.of(2026, 7, 21, 9, 15)),
                        any(),
                        eq(journalEntryId),
                        eq(InventoryAdjustmentPostingService.toSourceEventId("CYCLE_COUNT", ADJUSTMENT_ID)));
        verify(processedEvents).save(any());
        verifyNoInteractions(postingService);
    }

    @Test
    @DisplayName("Re-emitted adjustment (posting key already registered) is recorded with no journal entry of its own")
    void reEmittedAdjustmentRecordedAsDuplicate() {
        when(processedEvents.existsById("a-2")).thenReturn(false);
        when(adjustmentPostingService.postAdjustment(any())).thenReturn(null);

        listener.onInventoryEvent(adjustment("a-2", "3", "2.00", "STANDARD"));

        verify(ingestionRecorder)
                .recordPosted(anyString(), eq("a-2"), eq(ADJUSTMENT_ID), any(), any(), isNull(), any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Uncosted adjustment is never posted; recorded SKIPPED and marked processed")
    void uncostedAdjustmentSkipped() {
        when(processedEvents.existsById("a-3")).thenReturn(false);

        listener.onInventoryEvent(adjustment("a-3", "-2", "null", "NONE"));

        verifyNoInteractions(adjustmentPostingService);
        verify(ingestionRecorder)
                .recordUncostedSkip(
                        eq(InventoryAdjustedV1.EVENT_TYPE), eq("a-3"), eq(ADJUSTMENT_ID), any(), any(), anyString());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Uncosted scrap is recorded SKIPPED too")
    void uncostedScrapRecordedSkipped() {
        when(processedEvents.existsById("e-9")).thenReturn(false);

        listener.onInventoryEvent(uncostedScrap("e-9"));

        verify(ingestionRecorder)
                .recordUncostedSkip(eq(ScrapPostedV1.EVENT_TYPE), eq("e-9"), eq(SCRAP_ID), any(), any(), anyString());
    }

    @Test
    @DisplayName("Zero quantityDelta violates the contract: rejected and marked processed, never posted")
    void zeroDeltaAdjustmentRejected() {
        when(processedEvents.existsById("a-4")).thenReturn(false);

        listener.onInventoryEvent(adjustment("a-4", "0", "1.00", "AVERAGE"));

        verifyNoInteractions(adjustmentPostingService, ingestionRecorder);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Adjustment posting failures propagate unwrapped; nothing marked or recorded")
    void adjustmentPostingFailurePropagates() {
        when(processedEvents.existsById("a-5")).thenReturn(false);
        doThrow(new QueryTimeoutException("db down"))
                .when(adjustmentPostingService)
                .postAdjustment(any());

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onInventoryEvent(adjustment("a-5", "-1", "1.00", "AVERAGE")));

        verify(processedEvents, never()).save(any());
        verifyNoInteractions(ingestionRecorder);
    }

    // ===== inventory.product-value.changed (#2193) =====

    private static final UUID REVALUATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    /** Representative revaluation fact as pinned by {@link ProductValueChangedV1} (odoo-parity J4, #1054). */
    private String revaluation(String eventId, String previousUnitCost, String newUnitCost, String onHandQuantity) {
        return """
                {"eventId":"%s","eventType":"inventory.product-value.changed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-21T09:15:00Z","sourceService":"pos-inventory",
                 "payload":{"revaluationId":"%s","sku":"BRAKE-PAD-22","costingMethod":"AVERAGE",
                            "previousUnitCost":%s,"newUnitCost":%s,"onHandQuantity":%s,
                            "totalValueDelta":%s,"reason":"Supplier price correction","actor":"jdoe",
                            "occurredAt":"2026-07-21T09:15:00Z"}}
                """.formatted(
                        eventId,
                        REVALUATION_ID,
                        REVALUATION_ID,
                        previousUnitCost,
                        newUnitCost,
                        onHandQuantity,
                        delta(previousUnitCost, newUnitCost, onHandQuantity));
    }

    private static String delta(String previousUnitCost, String newUnitCost, String onHandQuantity) {
        java.math.BigDecimal previous =
                previousUnitCost == null ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(previousUnitCost);
        return new java.math.BigDecimal(newUnitCost)
                .subtract(previous)
                .multiply(new java.math.BigDecimal(onHandQuantity))
                .toPlainString();
    }

    @Test
    @DisplayName("Revaluation fact deserializes per the pinned schema, posts, and is recorded NEW with its entry")
    void revaluationPostsAndRecords() {
        UUID journalEntryId = UUID.randomUUID();
        when(processedEvents.existsById("r-1")).thenReturn(false);
        when(revaluationPostingService.postRevaluation(any())).thenReturn(journalEntryId);

        listener.onInventoryEvent(revaluation("r-1", "5.00", "7.25", "4"));

        ArgumentCaptor<ProductValueChangedV1> fact = ArgumentCaptor.forClass(ProductValueChangedV1.class);
        verify(revaluationPostingService).postRevaluation(fact.capture());
        assertThat(fact.getValue().revaluationId()).isEqualTo(REVALUATION_ID);
        assertThat(fact.getValue().sku()).isEqualTo("BRAKE-PAD-22");
        assertThat(fact.getValue().costingMethod()).isEqualTo("AVERAGE");
        assertThat(fact.getValue().previousUnitCost()).isEqualByComparingTo("5.00");
        assertThat(fact.getValue().newUnitCost()).isEqualByComparingTo("7.25");
        assertThat(fact.getValue().onHandQuantity()).isEqualByComparingTo("4");
        assertThat(fact.getValue().totalValueDelta()).isEqualByComparingTo("9.00");
        verify(ingestionRecorder)
                .recordPosted(
                        eq(ProductValueChangedV1.EVENT_TYPE),
                        eq("r-1"),
                        eq(REVALUATION_ID),
                        eq(java.time.LocalDateTime.of(2026, 7, 21, 9, 15)),
                        any(),
                        eq(journalEntryId),
                        eq(InventoryRevaluationPostingService.toSourceEventId(REVALUATION_ID)));
        verify(processedEvents).save(any());
        verifyNoInteractions(postingService, adjustmentPostingService);
    }

    @Test
    @DisplayName("Re-emitted revaluation (posting key already registered) is recorded with no journal entry of its own")
    void reEmittedRevaluationRecordedAsDuplicate() {
        when(processedEvents.existsById("r-2")).thenReturn(false);
        when(revaluationPostingService.postRevaluation(any())).thenReturn(null);

        listener.onInventoryEvent(revaluation("r-2", "5.00", "7.25", "4"));

        verify(ingestionRecorder)
                .recordPosted(anyString(), eq("r-2"), eq(REVALUATION_ID), any(), any(), isNull(), any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Zero value delta posts no journal entry but is still recorded PROCESSED, never SKIPPED")
    void zeroDeltaRevaluationRecordedProcessedNotSkipped() {
        when(processedEvents.existsById("r-3")).thenReturn(false);
        when(revaluationPostingService.postRevaluation(any())).thenReturn(null);

        listener.onInventoryEvent(revaluation("r-3", "5.00", "5.00", "4"));

        verify(revaluationPostingService).postRevaluation(any());
        verify(ingestionRecorder)
                .recordPosted(anyString(), eq("r-3"), eq(REVALUATION_ID), any(), any(), isNull(), any());
        verify(ingestionRecorder, never()).recordUncostedSkip(any(), any(), any(), any(), any(), any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Revaluation posting failures propagate unwrapped; nothing marked or recorded")
    void revaluationPostingFailurePropagates() {
        when(processedEvents.existsById("r-4")).thenReturn(false);
        doThrow(new QueryTimeoutException("db down"))
                .when(revaluationPostingService)
                .postRevaluation(any());

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onInventoryEvent(revaluation("r-4", "5.00", "3.00", "4")));

        verify(processedEvents, never()).save(any());
        verifyNoInteractions(ingestionRecorder);
    }
}

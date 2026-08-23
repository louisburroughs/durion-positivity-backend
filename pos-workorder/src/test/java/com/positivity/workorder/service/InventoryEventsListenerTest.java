package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ExtPickListReplica;
import com.positivity.workorder.internal.entity.ExtPickTaskReplica;
import com.positivity.workorder.internal.repository.ExtPickListReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPickTaskReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import com.positivity.workorder.internal.service.InventoryEventsListener;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class InventoryEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PICK_LIST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PICK_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID AVAILABILITY_AGGREGATE_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID PART_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID BACKORDER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtPickListReplicaRepository pickLists = mock(ExtPickListReplicaRepository.class);
    private final ExtPickTaskReplicaRepository pickTasks = mock(ExtPickTaskReplicaRepository.class);
    private final com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository availability =
            mock(com.positivity.workorder.internal.repository.ExtInventoryAvailabilityReplicaRepository.class);
    private final com.positivity.workorder.internal.repository.WorkorderPartRepository workorderParts =
            mock(com.positivity.workorder.internal.repository.WorkorderPartRepository.class);

    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                pickLists,
                pickTasks,
                availability,
                workorderParts,
                mock(ObjectProvider.class));
    }

    private String pickListEvent(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"inventory.pick-list.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"pickListId":"%s","workorderId":"%s","status":"READY_TO_PICK","priority":2}}
                """.formatted(eventId, PICK_LIST_ID, version, PICK_LIST_ID, WORKORDER_ID);
    }

    private String pickTaskEvent(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"inventory.pick-task.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"pickTaskId":"%s","pickListId":"%s","workorderId":"%s","skuId":"%s",
                            "locationId":"%s","quantityRequired":3,"quantityPicked":2,
                            "status":"PICKED","sortOrder":1}}
                """.formatted(
                        eventId, PICK_TASK_ID, version, PICK_TASK_ID, PICK_LIST_ID, WORKORDER_ID, SKU_ID, LOCATION_ID);
    }

    private String consumptionEvent(String eventId, int quantity) {
        return """
                {"eventId":"%s","eventType":"inventory.consumption.recorded","schemaVersion":1,
                 "aggregateId":"00000000-0000-0000-0000-00000000000c","aggregateVersion":1,
                 "payload":{"consumptionId":"00000000-0000-0000-0000-00000000000c","workorderId":"%s",
                            "pickListId":"%s","totalItemsConsumed":%d,
                            "lines":[{"pickTaskId":"%s","skuId":"%s","quantity":%d}]}}
                """.formatted(eventId, WORKORDER_ID, PICK_LIST_ID, quantity, PICK_TASK_ID, SKU_ID, quantity);
    }

    @Test
    @DisplayName("Materializes pick-list fact into ext_pick_list and records the eventId")
    void upsertsPickListReplica() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(pickLists.findById(PICK_LIST_ID)).thenReturn(Optional.empty());

        listener.onInventoryEvent(pickListEvent("e-1", 5));

        ArgumentCaptor<ExtPickListReplica> saved = ArgumentCaptor.forClass(ExtPickListReplica.class);
        verify(pickLists).save(saved.capture());
        assertThat(saved.getValue().getPickListId()).isEqualTo(PICK_LIST_ID);
        assertThat(saved.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("READY_TO_PICK");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Materializes pick-task fact into ext_pick_task preserving quantityConsumed")
    void upsertsPickTaskReplicaPreservingConsumed() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(pickTasks.findById(PICK_TASK_ID))
                .thenReturn(Optional.of(ExtPickTaskReplica.builder()
                        .pickTaskId(PICK_TASK_ID)
                        .quantityConsumed(2)
                        .aggregateVersion(1L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInventoryEvent(pickTaskEvent("e-2", 7));

        ArgumentCaptor<ExtPickTaskReplica> saved = ArgumentCaptor.forClass(ExtPickTaskReplica.class);
        verify(pickTasks).save(saved.capture());
        assertThat(saved.getValue().getQuantityPicked()).isEqualTo(2);
        assertThat(saved.getValue().getQuantityConsumed()).isEqualTo(2);
        assertThat(saved.getValue().getSkuId()).isEqualTo(SKU_ID);
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Consumption fact accumulates quantityConsumed on the task replica")
    void accumulatesConsumption() {
        when(processedEvents.existsById("e-3")).thenReturn(false);
        ExtPickTaskReplica task = ExtPickTaskReplica.builder()
                .pickTaskId(PICK_TASK_ID)
                .quantityPicked(3)
                .quantityConsumed(1)
                .aggregateVersion(1L)
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
        when(pickTasks.findById(PICK_TASK_ID)).thenReturn(Optional.of(task));

        listener.onInventoryEvent(consumptionEvent("e-3", 2));

        assertThat(task.getQuantityConsumed()).isEqualTo(3);
        verify(pickTasks).save(task);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onInventoryEvent(pickListEvent("e-dup", 5));

        verify(pickLists, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips stale snapshot facts whose aggregateVersion is below the replica's")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-old")).thenReturn(false);
        when(pickLists.findById(PICK_LIST_ID))
                .thenReturn(Optional.of(ExtPickListReplica.builder()
                        .pickListId(PICK_LIST_ID)
                        .status("COMPLETED")
                        .aggregateVersion(9L)
                        .updatedAt(Instant.now(TEST_CLOCK))
                        .build()));

        listener.onInventoryEvent(pickListEvent("e-old", 5));

        verify(pickLists, never()).save(any());
        // Still recorded as processed so the owner's manifest reconciles.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Ignored event types (lead-time, on-hand, ...) still record their eventId")
    void ignoredTypesStillRecordEventId() {
        when(processedEvents.existsById("e-other")).thenReturn(false);

        listener.onInventoryEvent("""
                {"eventId":"e-other","eventType":"inventory.lead-time.updated","payload":{}}
                """);

        verify(pickLists, never()).save(any());
        verify(pickTasks, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(pickLists.findById(PICK_LIST_ID)).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onInventoryEvent(pickListEvent("e-4", 1)));

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Applies an availability fact into the gate's replica")
    void appliesAvailabilityFact() {
        when(processedEvents.existsById("e-avail")).thenReturn(false);
        when(availability.findById(AVAILABILITY_AGGREGATE_ID)).thenReturn(Optional.empty());

        listener.onInventoryEvent("""
                {"eventId":"e-avail","eventType":"inventory.availability.updated","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":3,
                 "payload":{"stockItemId":"%s","locationId":"%s","onHandQuantity":9,"allocatedQuantity":2,
                            "availableToPromiseQuantity":7,"unitOfMeasure":"EA","incomingQuantity":0,
                            "outgoingQuantity":0,"projectedAvailableQuantity":7}}
                """.formatted(AVAILABILITY_AGGREGATE_ID, PRODUCT_ID, LOCATION_ID));

        ArgumentCaptor<com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica> saved =
                ArgumentCaptor.forClass(com.positivity.workorder.internal.entity.ExtInventoryAvailabilityReplica.class);
        verify(availability).save(saved.capture());
        assertThat(saved.getValue().getAvailableToPromiseQuantity()).isEqualByComparingTo("7");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Reverses an issue pos-inventory could not cover, and records the backorder")
    void reversesUncoveredIssue() {
        when(processedEvents.existsById("e-out-1")).thenReturn(false);
        com.positivity.workorder.internal.entity.WorkorderPart part =
                new com.positivity.workorder.internal.entity.WorkorderPart();
        part.setId(PART_ID);
        part.setQuantityIssued(new java.math.BigDecimal("3"));
        when(workorderParts.findById(PART_ID)).thenReturn(Optional.of(part));

        listener.onInventoryEvent(outcomeEvent("e-out-1", false));

        // The issue asserted metal left the shelf; the owner says it did not, so it goes back.
        assertThat(part.getQuantityIssued()).isEqualByComparingTo("1");
        assertThat(part.getBackorderId()).isEqualTo(BACKORDER_ID);
        assertThat(part.getReservationId()).isEqualTo(RESERVATION_ID);
        verify(workorderParts).save(part);
    }

    @Test
    @DisplayName("Leaves a covered issue standing and clears any backorder")
    void keepsCoveredIssue() {
        when(processedEvents.existsById("e-out-2")).thenReturn(false);
        com.positivity.workorder.internal.entity.WorkorderPart part =
                new com.positivity.workorder.internal.entity.WorkorderPart();
        part.setId(PART_ID);
        part.setQuantityIssued(new java.math.BigDecimal("3"));
        part.setBackorderId(BACKORDER_ID);
        when(workorderParts.findById(PART_ID)).thenReturn(Optional.of(part));

        listener.onInventoryEvent(outcomeEvent("e-out-2", true));

        assertThat(part.getQuantityIssued()).isEqualByComparingTo("3");
        assertThat(part.getBackorderId()).isNull();
    }

    @Test
    @DisplayName("Ignores a reservation outcome for a sales-order line")
    void ignoresSalesOrderOutcome() {
        when(processedEvents.existsById("e-out-3")).thenReturn(false);

        listener.onInventoryEvent("""
                {"eventId":"e-out-3","eventType":"inventory.reservation.outcome.recorded","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"reservationId":"%s","workorderLineId":null,"salesOrderLineId":"%s",
                            "stockItemId":"%s","requiredQuantity":2,"covered":true,"backorderId":null,
                            "occurredAt":"2026-08-17T12:00:00Z"}}
                """.formatted(RESERVATION_ID, RESERVATION_ID, PART_ID, PRODUCT_ID));

        // The topic carries both modules' outcomes; this one belongs to pos-order.
        verify(workorderParts, never()).save(any());
        verify(processedEvents).save(any());
    }

    private String outcomeEvent(String eventId, boolean covered) {
        return """
                {"eventId":"%s","eventType":"inventory.reservation.outcome.recorded","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":1,
                 "payload":{"reservationId":"%s","workorderLineId":"%s","salesOrderLineId":null,
                            "stockItemId":"%s","requiredQuantity":2,"covered":%s,"backorderId":%s,
                            "occurredAt":"2026-08-17T12:00:00Z"}}
                """.formatted(
                        eventId,
                        RESERVATION_ID,
                        RESERVATION_ID,
                        PART_ID,
                        PRODUCT_ID,
                        covered,
                        covered ? "null" : "\"" + BACKORDER_ID + "\"");
    }

    private String pickTaskEventV2(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"inventory.pick-task.updated","schemaVersion":2,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"pickTaskId":"%s","pickListId":"%s","workorderId":"%s","skuId":"%s",
                            "locationId":"%s","quantityRequired":3,"quantityPicked":2,
                            "status":"PICKED","sortOrder":1,"workorderLineId":"%s"}}
                """.formatted(
                eventId, PICK_TASK_ID, version, PICK_TASK_ID, PICK_LIST_ID, WORKORDER_ID, SKU_ID, LOCATION_ID, PART_ID);
    }

    /** #1479: the demand line is what lets a consumed pick find the part it came from. */
    @Test
    @DisplayName("a v2 pick-task fact carries its demand line onto the replica")
    void pickTaskReplicaKeepsTheDemandLine() {
        when(processedEvents.existsById("e-v2")).thenReturn(false);
        when(pickTasks.findById(PICK_TASK_ID)).thenReturn(Optional.empty());

        listener.onInventoryEvent(pickTaskEventV2("e-v2", 1));

        ArgumentCaptor<ExtPickTaskReplica> saved = ArgumentCaptor.forClass(ExtPickTaskReplica.class);
        verify(pickTasks).save(saved.capture());
        assertThat(saved.getValue().getWorkorderLineId()).isEqualTo(PART_ID);
    }

    /** A v1 fact carries no demand line; taking its null would drop a link already known. */
    @Test
    @DisplayName("a v1 pick-task fact does not erase a demand line already replicated")
    void pickTaskReplicaPreservesTheDemandLineAcrossV1Facts() {
        when(processedEvents.existsById("e-v1")).thenReturn(false);
        when(pickTasks.findById(PICK_TASK_ID))
                .thenReturn(Optional.of(ExtPickTaskReplica.builder()
                        .pickTaskId(PICK_TASK_ID)
                        .workorderLineId(PART_ID)
                        .aggregateVersion(1)
                        .build()));

        listener.onInventoryEvent(pickTaskEvent("e-v1", 2));

        ArgumentCaptor<ExtPickTaskReplica> saved = ArgumentCaptor.forClass(ExtPickTaskReplica.class);
        verify(pickTasks).save(saved.capture());
        assertThat(saved.getValue().getWorkorderLineId()).isEqualTo(PART_ID);
    }

    /**
     * #1479: a part picked and consumed through the pick flow used to leave
     * {@code workorder_part.quantity_consumed} at zero while the workorder completed.
     */
    @Test
    @DisplayName("consuming a pick task charges the workorder part it fulfils")
    void consumptionMovesTheWorkorderPartConsumedQuantity() {
        when(processedEvents.existsById("e-consume")).thenReturn(false);
        when(pickTasks.findById(PICK_TASK_ID))
                .thenReturn(Optional.of(ExtPickTaskReplica.builder()
                        .pickTaskId(PICK_TASK_ID)
                        .workorderLineId(PART_ID)
                        .quantityConsumed(0)
                        .build()));
        com.positivity.workorder.internal.entity.WorkorderPart part =
                com.positivity.workorder.internal.entity.WorkorderPart.builder()
                        .quantityConsumed(new java.math.BigDecimal("1"))
                        .build();
        part.setId(PART_ID);
        when(workorderParts.findById(PART_ID)).thenReturn(Optional.of(part));

        listener.onInventoryEvent(consumptionEvent("e-consume", 2));

        verify(workorderParts).save(part);
        assertThat(part.getQuantityConsumed()).isEqualByComparingTo("3");
    }

    /** A task with no demand line still records its own consumed quantity, and charges nothing. */
    @Test
    @DisplayName("a pick task with no demand line charges no part line")
    void consumptionWithoutADemandLineChargesNothing() {
        when(processedEvents.existsById("e-consume-orphan")).thenReturn(false);
        when(pickTasks.findById(PICK_TASK_ID))
                .thenReturn(Optional.of(ExtPickTaskReplica.builder()
                        .pickTaskId(PICK_TASK_ID)
                        .quantityConsumed(0)
                        .build()));

        listener.onInventoryEvent(consumptionEvent("e-consume-orphan", 2));

        verify(workorderParts, never()).save(any());
    }
}

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
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class InventoryEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PICK_LIST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PICK_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final ExtPickListReplicaRepository pickLists = mock(ExtPickListReplicaRepository.class);
    private final ExtPickTaskReplicaRepository pickTasks = mock(ExtPickTaskReplicaRepository.class);

    private InventoryEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, pickLists, pickTasks);
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
    @DisplayName("Ignored event types (availability, lead-time, ...) still record their eventId")
    void ignoredTypesStillRecordEventId() {
        when(processedEvents.existsById("e-other")).thenReturn(false);

        listener.onInventoryEvent("""
                {"eventId":"e-other","eventType":"inventory.availability.updated","payload":{}}
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
}

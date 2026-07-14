package com.positivity.inventory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.config.InventoryCommandListener;
import com.positivity.inventory.internal.dto.consumption.ConsumeItemsRequest;
import com.positivity.inventory.internal.dto.consumption.ConsumptionResponse;
import com.positivity.inventory.internal.dto.picklist.PickListResponse;
import com.positivity.inventory.internal.dto.picklist.PickTaskResponse;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.enums.PickListStatus;
import com.positivity.inventory.internal.enums.PickTaskStatus;
import com.positivity.inventory.internal.exception.PickScanMismatchException;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import com.positivity.inventory.service.ConsumptionService;
import com.positivity.inventory.service.OutboxReplayService;
import com.positivity.inventory.service.PickListService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

class InventoryCommandListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID PICK_LIST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID PICK_TASK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID SKU_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");

    private final OutboxReplayService replayService = mock(OutboxReplayService.class);
    private final PickListService pickListService = mock(PickListService.class);
    private final ConsumptionService consumptionService = mock(ConsumptionService.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);

    private InventoryCommandListener listener;

    @BeforeEach
    void setUp() {
        listener = new InventoryCommandListener(
                TEST_CLOCK, new ObjectMapper(), replayService, pickListService, consumptionService, processedEvents);
        ReflectionTestUtils.setField(listener, "replayMaxLookback", Duration.ofDays(30));
    }

    private String confirmCommand(String commandId) {
        return """
                {"commandType":"inventory.pick-task.confirm-requested","commandId":"%s",
                 "payload":{"pickListId":"%s","pickTaskId":"%s","scannedSkuId":"%s",
                            "scannedLocationId":"%s","quantityPicked":2}}
                """.formatted(commandId, PICK_LIST_ID, PICK_TASK_ID, SKU_ID, LOCATION_ID);
    }

    private String consumeCommand(String commandId) {
        return """
                {"commandType":"inventory.items.consume-requested","commandId":"%s",
                 "payload":{"workorderId":"%s","pickListId":"%s",
                            "items":[{"pickTaskId":"%s","skuId":"%s","quantity":2}]}}
                """.formatted(commandId, WORKORDER_ID, PICK_LIST_ID, PICK_TASK_ID, SKU_ID);
    }

    @Test
    @DisplayName("confirm-requested confirms the pick task and records the commandId")
    void confirmRequestedConfirmsTask() {
        when(processedEvents.existsById("c-1")).thenReturn(false);
        when(pickListService.confirmPickTask(PICK_LIST_ID, PICK_TASK_ID, SKU_ID, LOCATION_ID, 2))
                .thenReturn(new PickTaskResponse(
                        PICK_TASK_ID, PICK_LIST_ID, SKU_ID, LOCATION_ID, 3, 2, PickTaskStatus.PICKED, 1));

        listener.onCommand(confirmCommand("c-1"));

        verify(pickListService).confirmPickTask(PICK_LIST_ID, PICK_TASK_ID, SKU_ID, LOCATION_ID, 2);
        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("c-1");
        assertThat(processed.getValue().getOwner()).isEqualTo("inventory-commands");
    }

    @Test
    @DisplayName("consume-requested consumes the items and records the commandId")
    void consumeRequestedConsumesItems() {
        when(processedEvents.existsById("c-2")).thenReturn(false);
        when(consumptionService.consumePickedItems(any()))
                .thenReturn(new ConsumptionResponse(
                        UUID.randomUUID(), WORKORDER_ID, PICK_LIST_ID, 2, Instant.now(TEST_CLOCK), List.of()));

        listener.onCommand(consumeCommand("c-2"));

        ArgumentCaptor<ConsumeItemsRequest> request = ArgumentCaptor.forClass(ConsumeItemsRequest.class);
        verify(consumptionService).consumePickedItems(request.capture());
        assertThat(request.getValue().getWorkorderId()).isEqualTo(WORKORDER_ID);
        assertThat(request.getValue().getItems()).hasSize(1);
        assertThat(request.getValue().getItems().getFirst().getPickTaskId()).isEqualTo(PICK_TASK_ID);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("release-requested releases the pick list")
    void releaseRequestedReleasesPickList() {
        when(processedEvents.existsById("c-3")).thenReturn(false);
        when(pickListService.releasePickList(PICK_LIST_ID))
                .thenReturn(new PickListResponse(
                        PICK_LIST_ID, WORKORDER_ID, PickListStatus.READY_TO_PICK, 0, null, null, null));

        listener.onCommand("""
                {"commandType":"inventory.pick-list.release-requested","commandId":"c-3",
                 "payload":{"pickListId":"%s"}}
                """.formatted(PICK_LIST_ID));

        verify(pickListService).releasePickList(PICK_LIST_ID);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Duplicate commandId is skipped — retries apply each command at most once")
    void duplicateCommandIdIsSkipped() {
        when(processedEvents.existsById("c-dup")).thenReturn(true);

        listener.onCommand(confirmCommand("c-dup"));

        verify(pickListService, never()).confirmPickTask(any(), any(), any(), any(), anyInt());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Business validation failure is permanent: logged, commandId recorded, no retry")
    void businessFailureIsRecordedNotRetried() {
        when(processedEvents.existsById("c-bad")).thenReturn(false);
        when(pickListService.confirmPickTask(any(), any(), any(), any(), anyInt()))
                .thenThrow(new PickScanMismatchException(SKU_ID, LOCATION_ID));

        listener.onCommand(confirmCommand("c-bad"));

        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Pick commands without a commandId are ignored")
    void missingCommandIdIsIgnored() {
        listener.onCommand("""
                {"commandType":"inventory.pick-task.confirm-requested","payload":{"pickListId":"%s"}}
                """.formatted(PICK_LIST_ID));

        verify(pickListService, never()).confirmPickTask(any(), any(), any(), any(), anyInt());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Transient DB errors propagate so the container retries")
    void transientErrorsPropagate() {
        when(processedEvents.existsById(anyString())).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onCommand(confirmCommand("c-4")));

        verify(pickListService, never()).confirmPickTask(any(), any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("Unsupported command types and malformed messages are dropped without throwing")
    void unsupportedAndMalformedAreDropped() {
        listener.onCommand("{\"commandType\":\"inventory.something.else\",\"payload\":{}}");
        listener.onCommand("{not json");

        verify(pickListService, never()).confirmPickTask(any(), any(), any(), any(), anyInt());
        verify(replayService, never()).replaySince(any());
        verify(consumptionService, never()).consumePickedItems(any());
    }

    @Test
    @DisplayName("replay-requested still replays the window (regression, #899)")
    void replayStillWorks() {
        Instant since = Instant.now(TEST_CLOCK).minus(Duration.ofHours(2));

        listener.onCommand("""
                {"commandType":"inventory.outbox.replay-requested","payload":{"since":"%s"}}
                """.formatted(since));

        verify(replayService).replaySince(eq(since.minusSeconds(1)));
    }
}

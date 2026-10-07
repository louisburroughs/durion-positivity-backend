package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * #2579 (ADR-0044 §4): {@code order.outbox.replay-requested} on {@code order.commands.v1} re-queues the
 * drifted window of the requesting tenant's order facts, in the same command shape and with the same
 * window, look-back and failure rules as the other owners' command listeners.
 */
@DisplayName("PurchaseOrderCommandListener — order.outbox.replay-requested")
class PurchaseOrderCommandListenerReplayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC);

    private final OrderOutboxReplayService replayService = mock(OrderOutboxReplayService.class);
    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final PurchaseOrderServiceImpl purchaseOrderService = mock(PurchaseOrderServiceImpl.class);

    private final PurchaseOrderCommandListener listener = new PurchaseOrderCommandListener(
            CLOCK,
            new ObjectMapper(),
            processedEvents,
            mock(PurchaseOrderRepository.class),
            purchaseOrderService,
            replayService,
            Duration.ofDays(30),
            mock(PlatformTransactionManager.class));

    @Test
    @DisplayName("a bounded window is re-queued with a second of slack either side")
    void replaysTheWindow() {
        listener.onOrderCommand("""
                {"commandType":"order.outbox.replay-requested",
                 "payload":{"since":"2026-10-07T10:00:00Z","until":"2026-10-07T11:00:00Z"}}
                """);

        verify(replayService)
                .replayBetween(Instant.parse("2026-10-07T09:59:59Z"), Instant.parse("2026-10-07T11:00:01Z"));
    }

    @Test
    @DisplayName("an open window replays everything since its start")
    void replaysSince() {
        listener.onOrderCommand("""
                {"commandType":"ORDER_OUTBOX_REPLAY_REQUESTED","payload":{"since":"2026-10-07T10:00:00Z"}}
                """);

        verify(replayService).replaySince(Instant.parse("2026-10-07T09:59:59Z"));
    }

    @Test
    @DisplayName("a replay request carries no event id and is recorded nowhere, nor placed as a purchase order")
    void replayIsNotRecordedAsProcessed() {
        listener.onOrderCommand("""
                {"commandType":"order.outbox.replay-requested",
                 "payload":{"since":"2026-10-07T10:00:00Z","until":"2026-10-07T11:00:00Z"}}
                """);

        verifyNoInteractions(processedEvents, purchaseOrderService);
    }

    @Test
    @DisplayName("a request older than the look-back, a missing or malformed since, or another command replays nothing")
    void ignoresWhatItCannotServe() {
        listener.onOrderCommand("""
                {"commandType":"order.outbox.replay-requested","payload":{"since":"2026-01-01T00:00:00Z"}}
                """);
        listener.onOrderCommand("{\"commandType\":\"order.outbox.replay-requested\",\"payload\":{}}");
        listener.onOrderCommand("{\"commandType\":\"order.outbox.replay-requested\",\"payload\":{\"since\":\"x\"}}");
        listener.onOrderCommand("{\"commandType\":\"order.outbox.replay-requested\"}");
        listener.onOrderCommand("{\"commandType\":\"order.something-else\",\"payload\":{}}");
        listener.onOrderCommand("not json");

        verifyNoInteractions(replayService);
    }

    @Test
    @DisplayName("a transient database failure propagates for container retry")
    void transientFailurePropagates() {
        when(replayService.replayBetween(any(), any())).thenThrow(new QueryTimeoutException("timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class).isThrownBy(() -> listener.onOrderCommand("""
                {"commandType":"order.outbox.replay-requested",
                 "payload":{"since":"2026-10-07T10:00:00Z","until":"2026-10-07T11:00:00Z"}}
                """));
    }

    @Test
    @DisplayName("a permanent failure is logged and dropped rather than blocking the partition")
    void permanentFailureIsDropped() {
        when(replayService.replaySince(any())).thenThrow(new IllegalStateException("no tenant bound"));

        assertThatCode(() -> listener.onOrderCommand("""
                        {"commandType":"order.outbox.replay-requested","payload":{"since":"2026-10-07T10:00:00Z"}}
                        """)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a purchase-order command without a commandType still takes the event-id path, not the replay")
    void purchaseOrderCommandIsNotAReplay() {
        when(processedEvents.existsById("evt-1")).thenReturn(true);

        listener.onOrderCommand("""
                {"eventType":"order.purchase-order.requested","eventId":"evt-1","payload":{}}
                """);

        verify(processedEvents).existsById("evt-1");
        verify(processedEvents, never()).save(any());
        verifyNoInteractions(replayService);
    }
}

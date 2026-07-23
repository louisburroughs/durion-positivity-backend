package com.positivity.workorder.service;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
import com.positivity.workorder.internal.service.OrderEventsListener;
import com.positivity.workorder.internal.service.WorkorderStateMachine;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer-side contract test for {@code order.order.completed} finalize-back ingestion
 * (odoo-parity E3, issue #1084). The envelope JSON literal pins the settlement-relevant
 * OrderCompletedV1 fields (orderId, workorderId) so a producer-side rename fails here.
 */
class OrderEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final WorkorderStateMachine stateMachine = mock(WorkorderStateMachine.class);

    private OrderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, stateMachine);
    }

    private String orderCompleted(String eventId, String workorderIdJson) {
        return """
                {"eventId":"%s","eventType":"order.order.completed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,"sourceService":"pos-order",
                 "payload":{"orderId":"%s","orderNumber":"SO-1","locationId":null,
                            "workorderId":%s,"sessionId":null,"customerId":null,"vehicleId":null,
                            "clerkId":"clerk-1","terminalId":"terminal-1","currencyCode":"USD",
                            "subtotal":100.00,"discountTotal":0.00,"taxTotal":8.00,"grandTotal":108.00,
                            "lines":[],"tenders":[{"methodType":"CASH","amount":108.00}],
                            "completedAt":"2026-07-23T18:30:00Z"}}
                """.formatted(eventId, ORDER_ID, ORDER_ID, workorderIdJson);
    }

    @Test
    @DisplayName("Workorder-linked completion finalizes the workorder and marks the event processed")
    void workorderLinkedCompletionFinalizes() {
        when(processedEvents.existsById("e-1")).thenReturn(false);

        listener.onOrderEvent(orderCompleted("e-1", "\"" + WORKORDER_ID + "\""));

        verify(stateMachine).finalizeFromOrderSettlement(eq(WORKORDER_ID), eq(ORDER_ID), any());
        org.mockito.ArgumentCaptor<ProcessedEvent> processed =
                org.mockito.ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        org.assertj.core.api.Assertions.assertThat(processed.getValue().getEventId())
                .isEqualTo("e-1");
        org.assertj.core.api.Assertions.assertThat(processed.getValue().getOwner())
                .isEqualTo("order");
    }

    @Test
    @DisplayName("Completion with no workorderId is ignored but still marked processed")
    void nonWorkorderCompletionIgnored() {
        when(processedEvents.existsById("e-2")).thenReturn(false);

        listener.onOrderEvent(orderCompleted("e-2", "null"));

        verify(stateMachine, never()).finalizeFromOrderSettlement(any(), any(), any());
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Duplicate eventId is skipped without touching the state machine")
    void duplicateEventIdSkipped() {
        when(processedEvents.existsById("e-3")).thenReturn(true);

        listener.onOrderEvent(orderCompleted("e-3", "\"" + WORKORDER_ID + "\""));

        verifyNoInteractions(stateMachine);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Other order fact types are ignored but recorded so the owner manifest reconciles")
    void otherEventTypesIgnored() {
        when(processedEvents.existsById("e-4")).thenReturn(false);

        listener.onOrderEvent("""
                {"eventId":"e-4","eventType":"order.session.closed","payload":{}}
                """);

        verifyNoInteractions(stateMachine);
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Malformed payload is skipped but marked processed so the partition is not poisoned")
    void malformedPayloadSkippedAndMarkedProcessed() {
        when(processedEvents.existsById("e-5")).thenReturn(false);
        // A non-numeric grandTotal fails BigDecimal deserialization of the payload.
        String message = orderCompleted("e-5", "\"" + WORKORDER_ID + "\"").replace("108.00", "\"not-a-number\"");

        listener.onOrderEvent(message);

        verify(stateMachine, never()).finalizeFromOrderSettlement(any(), any(), any());
        verify(processedEvents).save(any(ProcessedEvent.class));
    }

    @Test
    @DisplayName("Unparsable message is skipped without recording anything")
    void unparsableMessageSkipped() {
        listener.onOrderEvent("this is not json");

        verifyNoInteractions(stateMachine);
        verifyNoInteractions(processedEvents);
    }

    @Test
    @DisplayName("Transient DB errors propagate unwrapped for container retry / DLQ")
    void transientErrorPropagates() {
        when(processedEvents.existsById("e-6")).thenReturn(false);
        doThrow(new QueryTimeoutException("db down"))
                .when(stateMachine)
                .finalizeFromOrderSettlement(any(), any(), any());

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onOrderEvent(orderCompleted("e-6", "\"" + WORKORDER_ID + "\"")));

        verify(processedEvents, never()).save(any());
    }
}

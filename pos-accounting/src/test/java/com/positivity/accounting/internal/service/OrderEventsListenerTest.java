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
import com.positivity.domainevents.order.RegisterSessionClosedV1;
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
 * Consumer-side contract test for {@code order.session.closed} ingestion (odoo-parity G3, issue
 * #1083). The envelope JSON literal pins the {@link RegisterSessionClosedV1} fact schema so a
 * producer-side field rename or type change fails here, not in production.
 */
class OrderEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-23T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final RegisterOverShortPostingService postingService = mock(RegisterOverShortPostingService.class);

    private OrderEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new OrderEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, postingService);
    }

    private String sessionClosed(String eventId) {
        return """
                {"eventId":"%s","eventType":"order.session.closed","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":0,
                 "occurredAtUtc":"2026-07-23T18:30:00Z","sourceService":"pos-order",
                 "payload":{"sessionId":"%s","terminalId":"terminal-1",
                            "locationId":"00000000-0000-0000-0000-0000000000aa",
                            "openedByClerkId":"clerk-1","closedByClerkId":"clerk-2",
                            "openingFloat":100.00,"countedCash":140.00,"theoreticalCash":150.00,
                            "overShort":-10.00,"varianceApproved":false,"currencyCode":"USD",
                            "tenderTotals":[{"methodType":"CASH","amount":50.00}],
                            "cashMovementTotal":0.00,
                            "openedAt":"2026-07-23T08:00:00Z","closedAt":"2026-07-23T18:30:00Z"}}
                """.formatted(eventId, SESSION_ID, SESSION_ID);
    }

    @Test
    @DisplayName("Session-closed fact deserializes per the pinned schema and is handed to posting")
    void sessionClosedPostsOverShort() {
        when(processedEvents.existsById("e-1")).thenReturn(false);

        listener.onOrderEvent(sessionClosed("e-1"));

        ArgumentCaptor<RegisterSessionClosedV1> fact = ArgumentCaptor.forClass(RegisterSessionClosedV1.class);
        verify(postingService).postOverShort(fact.capture());
        assertThat(fact.getValue().sessionId()).isEqualTo(SESSION_ID);
        assertThat(fact.getValue().terminalId()).isEqualTo("terminal-1");
        assertThat(fact.getValue().overShort()).isEqualByComparingTo(new BigDecimal("-10.00"));
        assertThat(fact.getValue().countedCash()).isEqualByComparingTo(new BigDecimal("140.00"));
        assertThat(fact.getValue().theoreticalCash()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(fact.getValue().closedAt()).isEqualTo(Instant.parse("2026-07-23T18:30:00Z"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getEventId()).isEqualTo("e-1");
        assertThat(processed.getValue().getProcessedAt()).isEqualTo(Instant.now(TEST_CLOCK));
    }

    @Test
    @DisplayName("Duplicate eventId is skipped without touching posting")
    void duplicateEventIdSkipped() {
        when(processedEvents.existsById("e-2")).thenReturn(true);

        listener.onOrderEvent(sessionClosed("e-2"));

        verifyNoInteractions(postingService);
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Other order fact types are ignored without recording their eventIds")
    void otherEventTypesIgnored() {
        listener.onOrderEvent("""
                {"eventId":"e-3","eventType":"order.order.completed","payload":{}}
                """);

        verifyNoInteractions(postingService);
        verifyNoInteractions(processedEvents);
    }

    @Test
    @DisplayName("Malformed payload is skipped but marked processed so the partition is not poisoned")
    void malformedPayloadSkippedAndMarkedProcessed() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        String message = sessionClosed("e-4").replace("-10.00", "\"not-a-number\"");

        listener.onOrderEvent(message);

        verify(postingService, never()).postOverShort(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Unparsable message is skipped without recording anything")
    void unparsableMessageSkipped() {
        listener.onOrderEvent("this is not json");

        verifyNoInteractions(postingService);
        verifyNoInteractions(processedEvents);
    }

    @Test
    @DisplayName("Posting failures propagate unwrapped for container retry / DLQ; nothing marked processed")
    void postingFailurePropagates() {
        when(processedEvents.existsById("e-5")).thenReturn(false);
        doThrow(new QueryTimeoutException("db down")).when(postingService).postOverShort(any());

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onOrderEvent(sessionClosed("e-5")));

        verify(processedEvents, never()).save(any());
    }
}
